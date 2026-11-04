/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.ide.runtime.session.events

import io.karpfen.dsl.states.State
import io.karpfen.dsl.states.StateMachine
import io.karpfen.dsl.states.Transition
import io.karpfen.dsl.states.actions.ActionBlock
import io.karpfen.dsl.states.actions.ActionOperationType
import io.karpfen.dsl.states.actions.ActionRule
import io.karpfen.dsl.states.actions.ValueActionRightSide
import io.karpfen.dsl.states.conditions.EventCondition
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.connection.SendOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleRefusal
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.EventPayload
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers the event injector: the event-level findings, availability (not yet, queued, live, unavailable), refusal on
 * errors, the frames handed to the sink for every payload kind, and what becomes of a send the connection cannot take.
 */
class EventInjectorTest {

    private val availability = MutableStateFlow<InjectionAvailability>(InjectionAvailability.Live)

    private val sink = RecordingSink()

    private val injector = EventInjector(catalog(), setOf("robot", "pump"), availability, sink)

    @Test
    fun `a listened event with a clean payload is sent as its frame, and the outcome carries the sink's frame text`() = runTest {
        val outcome = injector.inject(InjectionRequest("ops", "order", PayloadKind.JSON, "{\"count\": 2}"))

        assertEquals(listOf(EventFrame("ops", "order", EventPayload.Json("{\"count\": 2}"))), sink.frames)
        val sent = assertIs<InjectionOutcome.Sent>(outcome)
        assertEquals("frame 1", sent.frameText)
        assertTrue(sent.validation.codes.all { it == PayloadDiagnosticCodes.MEMBER_DEFAULTED })
    }

    @Test
    fun `each kind sends its own payload, and no payload drops the text`() = runTest {
        injector.inject(InjectionRequest("ops", "ping", PayloadKind.NONE, "ignored"))
        injector.inject(InjectionRequest("ops", "ping", PayloadKind.KMODEL, "make object \"p\":\"ping\" { }"))

        assertEquals(
            listOf(
                EventFrame("ops", "ping", EventPayload.None),
                EventFrame("ops", "ping", EventPayload.Kmodel("make object \"p\":\"ping\" { }")),
            ),
            sink.frames,
        )
        assertEquals(listOf(PayloadKind.NONE, PayloadKind.JSON, PayloadKind.KMODEL), PayloadKind.entries.map { injector.format(it).kind })
    }

    @Test
    fun `a finding that is an error refuses the event and sends nothing`() = runTest {
        val blank = injector.inject(InjectionRequest("ops", " ", PayloadKind.NONE, ""))
        val broken = injector.inject(InjectionRequest("ops", "order", PayloadKind.JSON, "{\"count\": "))

        assertEquals(PayloadDiagnosticCodes.EVENT_BLANK_NAME, assertIs<InjectionOutcome.Refused>(blank).validation.codes.first())
        assertTrue(PayloadDiagnosticCodes.JSON_SYNTAX in assertIs<InjectionOutcome.Refused>(broken).validation.codes)
        assertEquals(emptyList(), sink.frames)
    }

    @Test
    fun `an event no machine listens to and a domain named like an attached object are warned about, then sent`() = runTest {
        val outcome = injector.inject(InjectionRequest("robot", "poke", PayloadKind.NONE, ""))
        val unknown = injector.validate(InjectionRequest("elsewhere", "anything", PayloadKind.NONE, ""))

        assertEquals(
            listOf(PayloadDiagnosticCodes.EVENT_DOMAIN_IS_ATTACHMENT),
            assertIs<InjectionOutcome.Sent>(outcome).validation.codes,
        )
        assertEquals(listOf(PayloadDiagnosticCodes.EVENT_NOT_LISTENED), unknown.codes)
        assertEquals(1, sink.frames.size)
    }

    @Test
    fun `an event that is only raised is injectable, but nothing listens to it`() {
        val validation = injector.validate(InjectionRequest("ops", "done", PayloadKind.NONE, ""))

        assertEquals(listOf(PayloadDiagnosticCodes.EVENT_NOT_LISTENED), validation.codes)
    }

    @Test
    fun `while the environment only queues events, injecting warns that the event waits and its time to live runs`() = runTest {
        availability.value = InjectionAvailability.Queued

        val outcome = injector.inject(InjectionRequest("ops", "ping", PayloadKind.NONE, ""))

        assertEquals(listOf(PayloadDiagnosticCodes.EVENT_QUEUED_UNTIL_START), assertIs<InjectionOutcome.Sent>(outcome).validation.codes)
        assertEquals(listOf(PayloadDiagnosticCodes.EVENT_QUEUED_UNTIL_START), injector.validate(InjectionRequest("ops", "ping", PayloadKind.NONE, "")).codes)
    }

    @Test
    fun `before the deploy ends and while injection is unavailable nothing is sent`() = runTest {
        availability.value = InjectionAvailability.NotYet
        val early = injector.inject(InjectionRequest("ops", "ping", PayloadKind.NONE, ""))
        availability.value = InjectionAvailability.Unavailable("The environment stopped.")
        val late = injector.inject(InjectionRequest("ops", "ping", PayloadKind.NONE, ""))

        assertEquals(InjectionOutcome.Unavailable(InjectionAvailability.NotYet), early)
        assertEquals(InjectionOutcome.Unavailable(InjectionAvailability.Unavailable("The environment stopped.")), late)
        assertEquals(emptyList(), sink.frames)
    }

    @Test
    fun `a send the connection cannot take makes injection unavailable with the reason`() = runTest {
        val request = InjectionRequest("ops", "ping", PayloadKind.NONE, "")
        val refusal = LifecycleRefusal(LifecycleOperation.INJECT_EVENT, EnvironmentPhase.Halted, "The engine of the environment halted.")
        sink.answers += listOf(
            SendOutcome.NotConnected(ConnectionState.Reconnecting(2, null)),
            SendOutcome.NotAllowed(refusal),
            SendOutcome.Failed(TransportFailure.Interrupted("broken pipe")),
        )

        val outcomes = List(3) { injector.inject(request) }

        val reasons = outcomes.map { (assertIs<InjectionOutcome.Unavailable>(it).availability as InjectionAvailability.Unavailable).reason }
        assertTrue("reconnecting" in reasons[0])
        assertEquals("The engine of the environment halted.", reasons[1])
        assertTrue("broken pipe" in reasons[2])
        assertEquals(3, sink.frames.size)
    }

    @Test
    fun `an event the catalog does not list takes its payload type from the event metamodel by name`() {
        val validation = injector.validate(InjectionRequest("pumps", "order", PayloadKind.JSON, "{\"count\": \"2\"}"))

        assertTrue(PayloadDiagnosticCodes.NUMBER_EXPECTED in validation.codes)
        assertTrue(PayloadDiagnosticCodes.EVENT_NOT_LISTENED in validation.codes)
    }

    @Test
    fun `the injector offers the catalog and the availability it was given`() {
        assertEquals(listOf("ops", "robot"), injector.catalog.domains.map { it.name })
        assertEquals(availability, injector.availability)
    }

    /** The catalog of one machine attached to `robot` that listens to `order` and `ping` in `ops` and `poke` in `robot`, and raises `done`. */
    private fun catalog(): EventCatalog {
        val state = State("idle", ActionBlock(mutableListOf()), ActionBlock(mutableListOf(raise("ops", "done"))), mutableListOf())
        val machine = StateMachine(
            "Robot",
            mutableListOf(state),
            mutableListOf(
                Transition("idle", "idle", true, EventCondition("ops", "order")),
                Transition("idle", "idle", true, EventCondition("ops", "ping")),
                Transition("idle", "idle", true, EventCondition("robot", "poke")),
            ),
            mutableListOf(),
        )
        return EventCatalogBuilder.build(
            listOf("ops", "robot"),
            listOf(ListeningMachine("robot", Path.of("robot.kstates"), machine)),
            OrderEvents.metamodel,
            listOf("order", "ping"),
            OrderEvents.sources,
        )
    }

    /** The action that raises [name] in [domain]. */
    private fun raise(domain: String, name: String): ActionRule = ActionRule(ActionOperationType.EVENT, domain, ValueActionRightSide(name))

    /** A sink that records every frame and answers the programmed outcomes in order, else that the frame was written. */
    private class RecordingSink : EventSink {
        /** The frames handed over so far. */
        val frames = ArrayList<EventFrame>()

        /** The answers to the next sends. */
        val answers = ArrayDeque<SendOutcome>()

        /** Records [frame] and answers the next programmed outcome, or a written frame numbered by its send. */
        override suspend fun send(frame: EventFrame): SendOutcome {
            frames += frame
            return answers.removeFirstOrNull() ?: SendOutcome.Written("frame ${frames.size}")
        }
    }
}
