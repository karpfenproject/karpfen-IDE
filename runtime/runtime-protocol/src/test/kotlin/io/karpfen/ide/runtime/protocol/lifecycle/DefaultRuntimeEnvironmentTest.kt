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

import io.karpfen.ide.runtime.protocol.api.HttpRuntimeApi
import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.api.RuntimeTimeouts
import io.karpfen.ide.runtime.protocol.catalogue.AccessKey
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.doubles.ScriptedHttp
import io.karpfen.ide.runtime.protocol.golden.RuntimeWireWriter
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers [DefaultRuntimeEnvironment], the environment handle, over the real gateway and a scripted HTTP seam on virtual
 * time: refused calls send nothing, the uploads are kept, lifecycle calls move the phase, calls never overlap on the
 * wire, a cancelled activation completes and records its phase before the cancellation is rethrown, unknown outcomes and
 * rejections that show another phase are probed with a snapshot, a failed probe leaves the phase uncertain, a halted
 * engine shows only through a snapshot, a stopped environment stays stopped while the runtime still reports its last
 * tick, and an environment the runtime forgot is lost.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRuntimeEnvironmentTest {

    /** The environment of every handle. */
    private val environment = EnvironmentKey("env-1761555600000")

    /** The client of the calls that name one. */
    private val client = ClientId("karpfen-ide-4f2c9a1b7e03")

    /** The runtime's answers, scripted per test; unscripted calls are answered with 200 and an empty body. */
    private val http = ScriptedHttp()

    /** A handle over the gateway to [http] whose calls, timeouts included, run on the test's virtual time. */
    private fun TestScope.handle(timeouts: RuntimeTimeouts = RuntimeTimeouts()): DefaultRuntimeEnvironment =
        DefaultRuntimeEnvironment(environment, HttpRuntimeApi(http, timeouts, testScheduler.timeSource), StandardTestDispatcher(testScheduler))

    /** A snapshot body of the environment in [phase], as the runtime writes it. */
    private fun snapshot(phase: RuntimePhase): String = RuntimeWireWriter.snapshot(environment.value, phase, 0, emptyMap(), emptyList())

    /** The runtime's text of [text] for this environment. */
    private fun textOf(text: RuntimeText): String = text.render(mapOf("env" to environment.value))

    /** Uploads a metamodel and a model through [handle], which the unscripted runtime accepts. */
    private suspend fun configure(handle: RuntimeEnvironment) {
        assertEquals(CallOutcome.Done(Unit), handle.setMetamodel("metamodel"))
        assertEquals(CallOutcome.Done(Unit), handle.setModel("model"))
    }

    @Test
    fun `a new handle is configuring with nothing uploaded and refuses what the runtime would mishandle without sending it`() = runTest {
        val handle = handle()

        val refusals = listOf(
            handle.activate(),
            handle.start(),
            handle.stop(),
            handle.setModel("model"),
            handle.setEventDefinitions("events"),
            handle.forceState("lamp", "on"),
        ).map { assertIs<CallOutcome.Refused>(it).refusal }

        assertEquals(EnvironmentPhase.Configuring(Uploads.NONE), handle.phase.value)
        assertEquals(emptyList(), http.requests)
        assertEquals(
            listOf(
                LifecycleOperation.ACTIVATE,
                LifecycleOperation.START,
                LifecycleOperation.STOP,
                LifecycleOperation.SET_MODEL,
                LifecycleOperation.SET_EVENT_DEFINITIONS,
                LifecycleOperation.FORCE_STATE,
            ),
            refusals.map { it.operation },
        )
        refusals.forEach { refusal -> assertEquals(EnvironmentPhase.Configuring(Uploads.NONE), refusal.phase) }
        assertEquals(refusals.first(), handle.allows(LifecycleOperation.ACTIVATE))
        assertNull(handle.allows(LifecycleOperation.SET_METAMODEL))
        assertNotNull(handle.allows(LifecycleOperation.INJECT_EVENT))
    }

    @Test
    fun `the uploads are kept, and the metamodel is refused once the model was uploaded`() = runTest {
        val handle = handle()

        listOf(
            handle.setMetamodel("metamodel"),
            handle.setEventDefinitions("events"),
            handle.setModel("model"),
            handle.setStateMachine("living room", "machine"),
            handle.setTickDelay(50),
            handle.setEventTtl(3000),
            handle.registerObjectObserver(client, "lamp"),
            handle.registerDomainListener(client, "public"),
            handle.registerDomainListener(client, "control"),
        ).forEach { assertEquals(CallOutcome.Done(Unit), it) }
        val refused = handle.setMetamodel("another metamodel")

        assertEquals(
            EnvironmentPhase.Configuring(
                Uploads(
                    metamodel = true,
                    events = true,
                    model = true,
                    machines = setOf("living room"),
                    tickDelay = true,
                    eventTtl = true,
                    observers = 1,
                    listeners = 2,
                ),
            ),
            handle.phase.value,
        )
        assertEquals(LifecycleOperation.SET_METAMODEL, assertIs<CallOutcome.Refused>(refused).refusal.operation)
        assertEquals(
            listOf(
                "/setMetamodel", "/setEventDefinitions", "/setModel", "/setStateMachine", "/setTickDelay", "/setEventTtl",
                "/registerObjectObserver", "/registerDomainListener", "/registerDomainListener",
            ),
            http.paths,
        )
        assertEquals(listOf("envKey" to environment.value, "attachedTo" to "living room"), http.requests[3].query)
        assertEquals("machine", http.requests[3].body)
    }

    @Test
    fun `activation, start and stop move the phase, and a stopped environment answers a stop itself`() = runTest {
        val handle = handle()
        configure(handle)

        assertEquals(CallOutcome.Done(Unit), handle.activate())
        assertEquals(EnvironmentPhase.Activated, handle.phase.value)
        assertIs<CallOutcome.Refused>(handle.setTickDelay(50))
        assertEquals(CallOutcome.Done(Unit), handle.start())
        assertEquals(EnvironmentPhase.Running, handle.phase.value)
        assertIs<CallOutcome.Refused>(handle.start())
        assertEquals(CallOutcome.Done(Unit), handle.forceState("living room", "full power"))
        assertEquals(CallOutcome.Done(Unit), handle.stop())
        assertEquals(EnvironmentPhase.Stopped, handle.phase.value)
        assertEquals(CallOutcome.Done(Unit), handle.stop())
        assertIs<CallOutcome.Refused>(handle.issueAccessKey(client, Observatory.NoMachine))
        assertIs<CallOutcome.Refused>(handle.subscribeModel(client))
        assertIs<CallOutcome.Refused>(handle.activate())

        assertEquals(
            listOf("/setMetamodel", "/setModel", "/runEnvironment", "/startEnvironment", "/setActiveState", "/stopEnvironment"),
            http.paths,
        )
        assertEquals(EnvironmentPhase.Stopped, handle.phase.value)
    }

    @Test
    fun `a stopped environment stays stopped while the runtime still reports its last tick as running`() = runTest {
        val handle = handle()
        configure(handle)
        handle.activate()
        handle.start()
        assertEquals(CallOutcome.Done(Unit), handle.stop())
        http.ok(snapshot(RuntimePhase.RUNNING))
            .reject(409, RuntimeText.TICK_BOUNDARY_TIMEOUT.render(mapOf("env" to environment.value, "ms" to "10000")))

        val running = assertIs<CallOutcome.Done<RuntimeModelSnapshot>>(handle.snapshot()).value
        assertEquals(RuntimePhase.RUNNING, running.phase)
        assertEquals(EnvironmentPhase.Stopped, handle.phase.value)
        val timedOut = assertIs<CallOutcome.Failed>(handle.snapshot())
        assertEquals(RejectionKind.TICK_BOUNDARY_TIMEOUT, assertIs<RuntimeFailure.Rejected>(timedOut.failure).kind)
        assertEquals(EnvironmentPhase.Stopped, handle.phase.value)

        assertIs<CallOutcome.Refused>(handle.forceState("lamp", "on"))
        assertNotNull(handle.allows(LifecycleOperation.INJECT_EVENT))
        assertEquals(CallOutcome.Done(Unit), handle.stop())
        assertEquals(
            listOf(
                "/setMetamodel", "/setModel", "/runEnvironment", "/startEnvironment", "/stopEnvironment",
                "/observatory/model", "/observatory/model",
            ),
            http.paths,
        )
        assertEquals(EnvironmentPhase.Stopped, handle.phase.value)
    }

    @Test
    fun `access keys are issued through the route the observatory asks for`() = runTest {
        val handle = handle()
        val keys = (1..3).map { "ak-${client.value}-1761555601234-$it" }
        keys.forEach { http.ok(it) }

        assertEquals(CallOutcome.Done(AccessKey(keys[0])), handle.issueAccessKey(client, Observatory.NoMachine))
        assertEquals(CallOutcome.Done(AccessKey(keys[1])), handle.issueAccessKey(client, Observatory.EveryMachine))
        assertEquals(CallOutcome.Done(AccessKey(keys[2])), handle.issueAccessKey(client, Observatory.Machine("living room")))

        assertEquals(listOf("/registerClientForWebSocket", "/observatory/registerClient", "/observatory/registerClient"), http.paths)
        assertEquals(
            listOf(
                listOf("clientId" to client.value, "envKey" to environment.value),
                listOf("clientId" to client.value, "envKey" to environment.value, "modelElement" to "*"),
                listOf("clientId" to client.value, "envKey" to environment.value, "modelElement" to "living room"),
            ),
            http.requests.map { it.query },
        )
        assertEquals(EnvironmentPhase.Configuring(Uploads.NONE), handle.phase.value)
    }

    @Test
    fun `concurrent calls never overlap on the wire and go out in the order they were made`() = runTest {
        val handle = handle()
        repeat(5) { http.answer(200, after = 1.seconds) }
        val begin = testScheduler.currentTime

        val outcomes = listOf(
            async { handle.setMetamodel("metamodel") },
            async { handle.setStateMachine("lamp", "machine") },
            async { handle.setTickDelay(50) },
            async { handle.setEventTtl(3000) },
            async { handle.registerObjectObserver(client, "lamp") },
        ).awaitAll()

        outcomes.forEach { assertEquals(CallOutcome.Done(Unit), it) }
        assertEquals(1, http.mostOverlapping)
        assertEquals(listOf("/setMetamodel", "/setStateMachine", "/setTickDelay", "/setEventTtl", "/registerObjectObserver"), http.paths)
        assertEquals(5.seconds.inWholeMilliseconds, testScheduler.currentTime - begin)
    }

    @Test
    fun `a cancelled activation completes on the wire, records its phase and then rethrows the cancellation`() = runTest {
        val handle = handle()
        configure(handle)
        http.answer(200, after = 2.seconds)

        val activation = async { handle.activate() }
        advanceTimeBy(1.seconds)
        runCurrent()
        activation.cancel()
        advanceUntilIdle()

        assertTrue(activation.isCancelled)
        assertFailsWith<CancellationException> { activation.await() }
        assertEquals(EnvironmentPhase.Activated, handle.phase.value)
        assertEquals(listOf("/setMetamodel", "/setModel", "/runEnvironment"), http.paths)
        assertEquals(CallOutcome.Done(Unit), handle.start())
    }

    @Test
    fun `a cancelled upload is abandoned, leaves the phase as it was and frees the handle`() = runTest {
        val handle = handle()
        http.hang()

        val upload = async { handle.setMetamodel("metamodel") }
        runCurrent()
        upload.cancel()
        advanceUntilIdle()

        assertTrue(upload.isCancelled)
        assertEquals(EnvironmentPhase.Configuring(Uploads.NONE), handle.phase.value)
        assertEquals(CallOutcome.Done(Unit), handle.setMetamodel("metamodel"))
        assertEquals(EnvironmentPhase.Configuring(Uploads.NONE.copy(metamodel = true)), handle.phase.value)
    }

    @Test
    fun `an unknown outcome of a start is probed and the snapshot settles the phase`() = runTest {
        val handle = handle()
        configure(handle)
        handle.activate()
        http.fail(TransportFailure.Interrupted("Connection reset")).ok(snapshot(RuntimePhase.RUNNING))

        val outcome = handle.start()

        assertEquals(
            CallOutcome.Failed(RuntimeFailure.NoAnswer(RuntimeRoute.START_ENVIRONMENT, "Connection reset", timedOut = false)),
            outcome,
        )
        assertEquals(EnvironmentPhase.Running, handle.phase.value)
        assertEquals(listOf("/setMetamodel", "/setModel", "/runEnvironment", "/startEnvironment", "/observatory/model"), http.paths)
    }

    @Test
    fun `an internal error of an activation is probed too`() = runTest {
        val handle = handle()
        configure(handle)
        http.answer(500, "Unexpected error").ok(snapshot(RuntimePhase.ACTIVATED))

        assertEquals(CallOutcome.Failed(RuntimeFailure.ServerFault(RuntimeRoute.RUN_ENVIRONMENT, "Unexpected error")), handle.activate())
        assertEquals(EnvironmentPhase.Activated, handle.phase.value)
    }

    @Test
    fun `a probe that fails leaves the phase uncertain between the phases a start could have left until a snapshot resolves it`() = runTest {
        val handle = handle()
        configure(handle)
        handle.activate()
        http.hang().hang().ok(snapshot(RuntimePhase.HALTED))
        val begin = testScheduler.currentTime

        val outcome = assertIs<CallOutcome.Failed>(handle.start())

        assertTrue(assertIs<RuntimeFailure.NoAnswer>(outcome.failure).timedOut)
        assertEquals((10 + 30).seconds.inWholeMilliseconds, testScheduler.currentTime - begin)
        val uncertain = assertIs<EnvironmentPhase.Uncertain>(handle.phase.value)
        assertEquals(setOf(PhaseKind.ACTIVATED, PhaseKind.RUNNING, PhaseKind.HALTED), uncertain.candidates)
        assertTrue(uncertain.reason.isNotBlank())
        assertIs<CallOutcome.Refused>(handle.forceState("lamp", "on"))
        assertIs<CallOutcome.Refused>(handle.start())
        assertNull(handle.allows(LifecycleOperation.STOP))

        assertIs<CallOutcome.Done<RuntimeModelSnapshot>>(handle.snapshot())
        assertEquals(EnvironmentPhase.Halted, handle.phase.value)
        assertEquals(
            listOf("/setMetamodel", "/setModel", "/runEnvironment", "/startEnvironment", "/observatory/model", "/observatory/model"),
            http.paths,
        )
    }

    @Test
    fun `a rejection that shows another phase is probed, and one that shows nothing changes nothing`() = runTest {
        val handle = handle()
        configure(handle)
        http.reject(409, textOf(RuntimeText.MODELS_REQUIRED))
            .reject(409, textOf(RuntimeText.ALREADY_ACTIVE))
            .ok(snapshot(RuntimePhase.RUNNING))

        val modelsRequired = assertIs<CallOutcome.Failed>(handle.activate())
        assertEquals(RejectionKind.MODELS_REQUIRED, assertIs<RuntimeFailure.Rejected>(modelsRequired.failure).kind)
        assertEquals(EnvironmentPhase.Configuring(Uploads.NONE.copy(metamodel = true, model = true)), handle.phase.value)

        val alreadyActive = assertIs<CallOutcome.Failed>(handle.activate())
        assertEquals(RejectionKind.ALREADY_ACTIVE, assertIs<RuntimeFailure.Rejected>(alreadyActive.failure).kind)
        assertEquals(EnvironmentPhase.Running, handle.phase.value)
        assertEquals(listOf("/setMetamodel", "/setModel", "/runEnvironment", "/runEnvironment", "/observatory/model"), http.paths)
    }

    @Test
    fun `an unreachable runtime leaves the phase as it was without a probe`() = runTest {
        val handle = handle()
        configure(handle)
        http.fail(TransportFailure.Unreachable("Connection refused"))

        assertEquals(CallOutcome.Failed(RuntimeFailure.Unreachable(RuntimeRoute.RUN_ENVIRONMENT, "Connection refused")), handle.activate())
        assertEquals(EnvironmentPhase.Configuring(Uploads.NONE.copy(metamodel = true, model = true)), handle.phase.value)
        assertEquals(listOf("/setMetamodel", "/setModel", "/runEnvironment"), http.paths)
    }

    @Test
    fun `an environment the runtime no longer knows is lost and every operation is refused after`() = runTest {
        val handle = handle()
        http.reject(400, textOf(RuntimeText.UNKNOWN_ENVIRONMENT))

        val outcome = assertIs<CallOutcome.Failed>(handle.setMetamodel("metamodel"))

        assertEquals(RejectionKind.UNKNOWN_ENVIRONMENT, assertIs<RuntimeFailure.Rejected>(outcome.failure).kind)
        assertIs<EnvironmentPhase.Lost>(handle.phase.value)
        LifecycleOperation.entries.forEach { operation -> assertNotNull(handle.allows(operation), "$operation") }
        assertIs<CallOutcome.Refused>(handle.snapshot())
        assertIs<CallOutcome.Refused>(handle.stop())
        assertEquals(listOf("/setMetamodel"), http.paths)
    }

    @Test
    fun `a snapshot reports the phase, a halted engine among them, and its rejections settle what they tell`() = runTest {
        val handle = handle()
        http.reject(409, textOf(RuntimeText.NO_MODEL))
        assertIs<CallOutcome.Failed>(handle.snapshot())
        assertEquals(EnvironmentPhase.Configuring(Uploads.NONE), handle.phase.value)

        configure(handle)
        handle.activate()
        http.reject(409, RuntimeText.TICK_BOUNDARY_TIMEOUT.render(mapOf("env" to environment.value, "ms" to "10000")))
        assertIs<CallOutcome.Failed>(handle.snapshot())
        assertEquals(EnvironmentPhase.Running, handle.phase.value)

        http.reject(409, RuntimeText.REFERENCE_LOOP.render(mapOf("id" to "lamp"))).ok(snapshot(RuntimePhase.HALTED))
        assertIs<CallOutcome.Failed>(handle.snapshot())
        assertEquals(EnvironmentPhase.Running, handle.phase.value)
        val halted = assertIs<CallOutcome.Done<RuntimeModelSnapshot>>(handle.snapshot()).value
        assertEquals(RuntimePhase.HALTED, halted.phase)
        assertEquals(EnvironmentPhase.Halted, handle.phase.value)

        assertIs<CallOutcome.Refused>(handle.start())
        assertIs<CallOutcome.Refused>(handle.forceState("lamp", "on"))
        assertEquals(CallOutcome.Done(Unit), handle.stop())
        assertEquals(EnvironmentPhase.Stopped, handle.phase.value)
    }

    @Test
    fun `reading a source and the model subscription leave the phase as it was`() = runTest {
        val handle = handle()
        http.ok("STATEMACHINE ATTACHED TO lamp : Lamp\n")

        assertEquals(CallOutcome.Done("STATEMACHINE ATTACHED TO lamp : Lamp\n"), handle.stateMachineSource("lamp"))
        assertEquals(CallOutcome.Done(Unit), handle.subscribeModel(client))
        assertEquals(CallOutcome.Done(Unit), handle.unsubscribeModel(client))

        assertEquals(EnvironmentPhase.Configuring(Uploads.NONE), handle.phase.value)
        assertEquals(listOf("/observatory/statemachine", "/observatory/subscribeModel", "/observatory/unsubscribeModel"), http.paths)
    }
}
