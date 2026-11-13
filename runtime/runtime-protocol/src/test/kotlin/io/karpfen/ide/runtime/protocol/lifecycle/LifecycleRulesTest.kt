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
package io.karpfen.ide.runtime.protocol.lifecycle

import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.ACTIVATE
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.ACTIVATE_FEATURE
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.DEACTIVATE_FEATURE
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.FORCE_STATE
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.INJECT_EVENT
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.ISSUE_ACCESS_KEY
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.MESSAGE_FEATURE
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.READ_ACTIVE_FEATURES
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.READ_SNAPSHOT
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.READ_STATE_MACHINE_SOURCE
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.REGISTER_DOMAIN_LISTENER
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.REGISTER_OBJECT_OBSERVER
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.SET_EVENT_DEFINITIONS
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.SET_EVENT_TTL
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.SET_METAMODEL
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.SET_MODEL
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.SET_STATE_MACHINE
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.SET_TICK_DELAY
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.START
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.STOP
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.SUBSCRIBE_MODEL
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation.UNSUBSCRIBE_MODEL
import io.karpfen.ide.runtime.protocol.message.ProtocolViolation
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.protocol.message.ViolationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers [LifecycleRules] exhaustively. The tests check the verdict of every operation in every phase against the
 * lifecycle's verdict table, and the phase after every success. They check the resolution of every kind of failure and
 * every rejection kind for every operation against the transition table. They also check every probe result, the
 * reconciliation with a snapshot's phase, and a stopped environment that stays stopped.
 */
class LifecycleRulesTest {

    /** Nothing uploaded. */
    private val nothing = Uploads.NONE
    /** Only the metamodel uploaded. */
    private val metamodel = nothing.copy(metamodel = true)
    /** The metamodel and the model uploaded. */
    private val metamodelAndModel = metamodel.copy(model = true)
    /** The metamodel and the event definitions uploaded. */
    private val metamodelAndEvents = metamodel.copy(events = true)
    /** A phase left uncertain by a start. */
    private val uncertain = EnvironmentPhase.Uncertain(setOf(PhaseKind.ACTIVATED, PhaseKind.RUNNING, PhaseKind.HALTED), "start", metamodelAndModel)
    /** A lost environment. */
    private val lost = EnvironmentPhase.Lost("the runtime restarted")

    /** The configuring calls that need nothing uploaded before. */
    private val configuringCalls = setOf(SET_STATE_MACHINE, SET_TICK_DELAY, SET_EVENT_TTL, REGISTER_OBJECT_OBSERVER, REGISTER_DOMAIN_LISTENER)
    /** The reads, allowed unless the environment is lost. */
    private val reads = setOf(READ_STATE_MACHINE_SOURCE, READ_SNAPSHOT)
    /** The feature operations, allowed in every phase of an existing environment. */
    private val features = setOf(ACTIVATE_FEATURE, DEACTIVATE_FEATURE, READ_ACTIVE_FEATURES, MESSAGE_FEATURE)

    /**
     * The verdict table. For each phase it lists the operations that are sent. Every other operation is refused, and a
     * stop of a stopped environment is answered locally.
     */
    private val sent: Map<EnvironmentPhase, Set<LifecycleOperation>> = mapOf(
        EnvironmentPhase.Configuring(nothing) to
            features + configuringCalls + reads + setOf(SET_METAMODEL, ISSUE_ACCESS_KEY, SUBSCRIBE_MODEL, UNSUBSCRIBE_MODEL),
        EnvironmentPhase.Configuring(metamodel) to
            features + configuringCalls + reads + setOf(SET_METAMODEL, SET_EVENT_DEFINITIONS, SET_MODEL, ISSUE_ACCESS_KEY, SUBSCRIBE_MODEL, UNSUBSCRIBE_MODEL),
        EnvironmentPhase.Configuring(metamodelAndEvents) to
            features + configuringCalls + reads + setOf(SET_EVENT_DEFINITIONS, SET_MODEL, ISSUE_ACCESS_KEY, SUBSCRIBE_MODEL, UNSUBSCRIBE_MODEL),
        EnvironmentPhase.Configuring(metamodelAndModel) to
            features + configuringCalls + reads + setOf(SET_EVENT_DEFINITIONS, SET_MODEL, ISSUE_ACCESS_KEY, ACTIVATE, SUBSCRIBE_MODEL, UNSUBSCRIBE_MODEL),
        EnvironmentPhase.Configuring(nothing.copy(model = true)) to
            features + configuringCalls + reads + setOf(ISSUE_ACCESS_KEY, SUBSCRIBE_MODEL, UNSUBSCRIBE_MODEL),
        EnvironmentPhase.Activated to
            features + reads + setOf(ISSUE_ACCESS_KEY, START, STOP, FORCE_STATE, SUBSCRIBE_MODEL, UNSUBSCRIBE_MODEL, INJECT_EVENT),
        EnvironmentPhase.Running to
            features + reads + setOf(ISSUE_ACCESS_KEY, STOP, FORCE_STATE, SUBSCRIBE_MODEL, UNSUBSCRIBE_MODEL, INJECT_EVENT),
        EnvironmentPhase.Halted to features + reads + setOf(ISSUE_ACCESS_KEY, STOP, SUBSCRIBE_MODEL, UNSUBSCRIBE_MODEL),
        EnvironmentPhase.Stopped to features + reads + setOf(UNSUBSCRIBE_MODEL),
        uncertain to features + reads + setOf(STOP, UNSUBSCRIBE_MODEL),
        lost to emptySet(),
    )

    /** The route of the failures, which the rules do not look at. */
    private val route = RuntimeRoute.RUN_ENVIRONMENT

    /** A rejection of [kind]. */
    private fun rejected(kind: RejectionKind) = RuntimeFailure.Rejected(route, 409, kind, "text", emptyMap())

    /** The failures that leave open whether the call was applied. */
    private val unknownOutcomes: List<RuntimeFailure> = listOf(
        RuntimeFailure.NoAnswer(route, "no answer", timedOut = true),
        RuntimeFailure.NoAnswer(route, "Connection reset", timedOut = false),
        RuntimeFailure.ServerFault(route, "boom"),
        RuntimeFailure.UnexpectedStatus(route, 502, ""),
        RuntimeFailure.ProtocolViolated(route, ProtocolViolation(ViolationKind.UNEXPECTED_BODY, "odd", "")),
    )

    /** A snapshot that reports [phase]. */
    private fun snapshot(phase: RuntimePhase) = RuntimeModelSnapshot(EnvironmentKey("env-1"), phase, 3, emptyMap(), emptyList())

    @Test
    fun `every operation in every phase gets the verdict of the table`() {
        assertEquals(PhaseKind.entries.size + 6, sent.size)
        sent.forEach { (phase, operations) ->
            LifecycleOperation.entries.forEach { operation ->
                val verdict = LifecycleRules.verdict(phase, operation)
                when {
                    phase == EnvironmentPhase.Stopped && operation == STOP -> assertEquals(Verdict.AnswerLocally, verdict, "$operation in $phase")
                    operation in operations -> assertEquals(Verdict.Send, verdict, "$operation in $phase")
                    else -> assertTrue(assertIs<Verdict.Refuse>(verdict, "$operation in $phase").reason.isNotBlank())
                }
            }
        }
    }

    @Test
    fun `a success of a configuring call adds to the uploads and lifecycle calls move the phase`() {
        val start = EnvironmentPhase.Configuring(nothing)
        val expected = mapOf(
            SET_METAMODEL to nothing.copy(metamodel = true),
            SET_EVENT_DEFINITIONS to nothing.copy(events = true),
            SET_MODEL to nothing.copy(model = true),
            SET_STATE_MACHINE to nothing.copy(machines = setOf("lamp")),
            SET_TICK_DELAY to nothing.copy(tickDelay = true),
            SET_EVENT_TTL to nothing.copy(eventTtl = true),
            REGISTER_OBJECT_OBSERVER to nothing.copy(observers = 1),
            REGISTER_DOMAIN_LISTENER to nothing.copy(listeners = 1),
        )
        LifecycleOperation.entries.forEach { operation ->
            val after = LifecycleRules.afterSuccess(start, operation, attachedTo = "lamp")
            val wanted = when (operation) {
                ACTIVATE -> EnvironmentPhase.Activated
                START -> EnvironmentPhase.Running
                STOP -> EnvironmentPhase.Stopped
                else -> EnvironmentPhase.Configuring(expected[operation] ?: nothing)
            }
            assertEquals(wanted, after, "$operation")
        }
        assertEquals(
            EnvironmentPhase.Configuring(nothing.copy(machines = setOf("lamp", "switch"), observers = 2)),
            listOf(SET_STATE_MACHINE to "lamp", REGISTER_OBJECT_OBSERVER to null, SET_STATE_MACHINE to "switch", REGISTER_OBJECT_OBSERVER to null)
                .fold(start as EnvironmentPhase) { phase, (operation, key) -> LifecycleRules.afterSuccess(phase, operation, key) },
        )
        assertEquals(EnvironmentPhase.Running, LifecycleRules.afterSuccess(EnvironmentPhase.Running, FORCE_STATE))
    }

    @Test
    fun `nothing sent changes nothing, and an unknown outcome is probed only for activate, start and stop`() {
        sent.keys.forEach { phase ->
            LifecycleOperation.entries.forEach { operation ->
                assertEquals(
                    OutcomeResolution.Settled(phase),
                    LifecycleRules.afterFailure(phase, operation, RuntimeFailure.Unreachable(route, "refused")),
                )
                unknownOutcomes.forEach { failure ->
                    val expected = if (operation in setOf(ACTIVATE, START, STOP)) OutcomeResolution.Probe else OutcomeResolution.Settled(phase)
                    assertEquals(expected, LifecycleRules.afterFailure(phase, operation, failure), "$failure of $operation in $phase")
                }
            }
        }
    }

    @Test
    fun `every rejection of every operation in every phase follows the transition table`() {
        val probedByReconcile = setOf(RejectionKind.ALREADY_ACTIVE, RejectionKind.NOT_ACTIVE, RejectionKind.NO_EXECUTION_THREAD)
        sent.keys.forEach { phase ->
            LifecycleOperation.entries.forEach { operation ->
                RejectionKind.entries.forEach { kind ->
                    val expected: OutcomeResolution = when {
                        operation == ACTIVATE -> when (kind) {
                            RejectionKind.ALREADY_ACTIVE -> OutcomeResolution.Probe
                            RejectionKind.UNKNOWN_ENVIRONMENT -> lostResolution()
                            else -> OutcomeResolution.Settled(phase)
                        }
                        operation in setOf(START, STOP) && kind == RejectionKind.NOT_ACTIVATED -> OutcomeResolution.Probe
                        operation == READ_SNAPSHOT && kind in setOf(RejectionKind.NO_MODEL, RejectionKind.REFERENCE_LOOP) ->
                            OutcomeResolution.Settled(phase)
                        operation == READ_SNAPSHOT && kind == RejectionKind.TICK_BOUNDARY_TIMEOUT ->
                            OutcomeResolution.Settled(if (phase == EnvironmentPhase.Stopped) phase else EnvironmentPhase.Running)
                        kind == RejectionKind.UNKNOWN_ENVIRONMENT -> lostResolution()
                        kind in probedByReconcile -> OutcomeResolution.Probe
                        else -> OutcomeResolution.Settled(phase)
                    }
                    val actual = LifecycleRules.afterFailure(phase, operation, rejected(kind))
                    if (expected == lostResolution()) {
                        assertIs<EnvironmentPhase.Lost>(assertIs<OutcomeResolution.Settled>(actual, "$kind of $operation in $phase").phase)
                    } else {
                        assertEquals(expected, actual, "$kind of $operation in $phase")
                    }
                }
            }
        }
    }

    @Test
    fun `a lost environment names the rejection that showed it`() {
        val resolution = LifecycleRules.afterFailure(EnvironmentPhase.Running, SET_MODEL, rejected(RejectionKind.UNKNOWN_ENVIRONMENT))

        val reason = assertIs<EnvironmentPhase.Lost>(assertIs<OutcomeResolution.Settled>(resolution).phase).reason
        assertTrue("POST /runEnvironment" in reason, reason)
    }

    @Test
    fun `a probe's snapshot settles the phase, keeping the known uploads of a configuring environment`() {
        val configuring = EnvironmentPhase.Configuring(metamodelAndModel)
        RuntimePhase.entries.forEach { reported ->
            val expected = when (reported) {
                RuntimePhase.CONFIGURING -> configuring
                RuntimePhase.ACTIVATED -> EnvironmentPhase.Activated
                RuntimePhase.RUNNING -> EnvironmentPhase.Running
                RuntimePhase.HALTED -> EnvironmentPhase.Halted
                RuntimePhase.STOPPED -> EnvironmentPhase.Stopped
            }
            assertEquals(expected, LifecycleRules.afterProbe(configuring, ACTIVATE, ProbeResult.Reported(snapshot(reported))))
            assertEquals(expected, LifecycleRules.afterSnapshot(configuring, snapshot(reported)))
        }
        assertEquals(
            EnvironmentPhase.Configuring(metamodelAndModel),
            LifecycleRules.afterSnapshot(uncertain, snapshot(RuntimePhase.CONFIGURING)),
        )
        assertEquals(EnvironmentPhase.Configuring(nothing), LifecycleRules.afterSnapshot(EnvironmentPhase.Running, snapshot(RuntimePhase.CONFIGURING)))
        assertEquals(EnvironmentPhase.Halted, LifecycleRules.afterSnapshot(EnvironmentPhase.Running, snapshot(RuntimePhase.HALTED)))
    }

    @Test
    fun `a failed probe settles what its rejection tells and leaves the rest uncertain between the possible phases`() {
        val configuring = EnvironmentPhase.Configuring(metamodelAndModel)
        fun probe(kind: RejectionKind) = ProbeResult.Failed(RuntimeFailure.Rejected(RuntimeRoute.MODEL_SNAPSHOT, 409, kind, "text", emptyMap()))
        val timedOut = ProbeResult.Failed(RuntimeFailure.NoAnswer(RuntimeRoute.MODEL_SNAPSHOT, "no answer", timedOut = true))

        assertEquals(configuring, LifecycleRules.afterProbe(configuring, ACTIVATE, probe(RejectionKind.NO_MODEL)))
        assertEquals(EnvironmentPhase.Running, LifecycleRules.afterProbe(configuring, ACTIVATE, probe(RejectionKind.TICK_BOUNDARY_TIMEOUT)))
        assertIs<EnvironmentPhase.Lost>(LifecycleRules.afterProbe(configuring, ACTIVATE, probe(RejectionKind.UNKNOWN_ENVIRONMENT)))

        val afterActivate = assertIs<EnvironmentPhase.Uncertain>(LifecycleRules.afterProbe(configuring, ACTIVATE, timedOut))
        assertEquals(setOf(PhaseKind.CONFIGURING, PhaseKind.ACTIVATED), afterActivate.candidates)
        assertEquals(metamodelAndModel, afterActivate.uploads)
        assertTrue(afterActivate.reason.isNotBlank())
        assertEquals(
            setOf(PhaseKind.ACTIVATED, PhaseKind.RUNNING, PhaseKind.HALTED),
            assertIs<EnvironmentPhase.Uncertain>(LifecycleRules.afterProbe(EnvironmentPhase.Activated, START, probe(RejectionKind.REFERENCE_LOOP))).candidates,
        )
        assertEquals(
            setOf(PhaseKind.RUNNING, PhaseKind.STOPPED),
            assertIs<EnvironmentPhase.Uncertain>(LifecycleRules.afterProbe(EnvironmentPhase.Running, STOP, timedOut)).candidates,
        )
        assertEquals(
            setOf(PhaseKind.ACTIVATED, PhaseKind.RUNNING, PhaseKind.HALTED, PhaseKind.STOPPED),
            assertIs<EnvironmentPhase.Uncertain>(LifecycleRules.afterProbe(uncertain, STOP, timedOut)).candidates,
        )
        assertEquals(
            setOf(PhaseKind.ACTIVATED, PhaseKind.RUNNING, PhaseKind.HALTED, PhaseKind.STOPPED),
            assertIs<EnvironmentPhase.Uncertain>(LifecycleRules.afterProbe(configuring, SET_TICK_DELAY, timedOut)).candidates,
        )
    }

    @Test
    fun `a stopped environment stays stopped whatever the runtime reports while its last tick ends, until it is lost`() {
        val stopped = EnvironmentPhase.Stopped
        fun failed(kind: RejectionKind) =
            ProbeResult.Failed(RuntimeFailure.Rejected(RuntimeRoute.MODEL_SNAPSHOT, 409, kind, "text", emptyMap()))
        val timedOut = ProbeResult.Failed(RuntimeFailure.NoAnswer(RuntimeRoute.MODEL_SNAPSHOT, "no answer", timedOut = true))

        assertEquals(
            OutcomeResolution.Settled(stopped),
            LifecycleRules.afterFailure(stopped, READ_SNAPSHOT, rejected(RejectionKind.TICK_BOUNDARY_TIMEOUT)),
        )
        RuntimePhase.entries.forEach { reported ->
            assertEquals(stopped, LifecycleRules.afterSnapshot(stopped, snapshot(reported)), "a snapshot that reports $reported")
            LifecycleOperation.entries.forEach { operation ->
                val probe = ProbeResult.Reported(snapshot(reported))
                assertEquals(stopped, LifecycleRules.afterProbe(stopped, operation, probe), "a probe for $operation that reports $reported")
            }
        }
        val failedProbes = listOf(
            failed(RejectionKind.TICK_BOUNDARY_TIMEOUT),
            failed(RejectionKind.NO_MODEL),
            failed(RejectionKind.REFERENCE_LOOP),
            timedOut,
        )
        failedProbes.forEach { probe -> assertEquals(stopped, LifecycleRules.afterProbe(stopped, UNSUBSCRIBE_MODEL, probe), "$probe") }
        assertIs<EnvironmentPhase.Lost>(LifecycleRules.afterProbe(stopped, UNSUBSCRIBE_MODEL, failed(RejectionKind.UNKNOWN_ENVIRONMENT)))
        val forgotten = LifecycleRules.afterFailure(stopped, READ_SNAPSHOT, rejected(RejectionKind.UNKNOWN_ENVIRONMENT))
        assertIs<EnvironmentPhase.Lost>(assertIs<OutcomeResolution.Settled>(forgotten).phase)
        assertEquals(EnvironmentPhase.Running, LifecycleRules.afterSnapshot(uncertain, snapshot(RuntimePhase.RUNNING)))
        val stopWithoutBoundary = LifecycleRules.afterProbe(EnvironmentPhase.Running, STOP, failed(RejectionKind.TICK_BOUNDARY_TIMEOUT))
        assertEquals(EnvironmentPhase.Running, stopWithoutBoundary)
    }

    @Test
    fun `phases map to their kinds`() {
        assertEquals(PhaseKind.CONFIGURING, LifecycleRules.kindOf(EnvironmentPhase.Configuring(nothing)))
        assertEquals(PhaseKind.ACTIVATED, LifecycleRules.kindOf(EnvironmentPhase.Activated))
        assertEquals(PhaseKind.RUNNING, LifecycleRules.kindOf(EnvironmentPhase.Running))
        assertEquals(PhaseKind.HALTED, LifecycleRules.kindOf(EnvironmentPhase.Halted))
        assertEquals(PhaseKind.STOPPED, LifecycleRules.kindOf(EnvironmentPhase.Stopped))
        assertEquals(null, LifecycleRules.kindOf(uncertain))
        assertEquals(null, LifecycleRules.kindOf(lost))
    }

    /** Stands for a settled lost phase, whose reason the transition tests do not compare. */
    private fun lostResolution(): OutcomeResolution = OutcomeResolution.Settled(lost)
}
