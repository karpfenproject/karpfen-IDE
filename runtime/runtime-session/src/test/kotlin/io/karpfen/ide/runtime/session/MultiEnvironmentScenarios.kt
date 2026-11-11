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
package io.karpfen.ide.runtime.session

import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.api.ActiveEnvironment
import io.karpfen.ide.runtime.protocol.api.RuntimeResult
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.protocol.conformance.eventually
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.transport.ktor.ktorTransports
import io.karpfen.ide.runtime.session.mirror.MirrorSnapshot
import io.karpfen.ide.runtime.session.mirror.ObjectRef
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Covers several environments in one runtime against a real runtime. They are deployed and driven through sessions of
 * one client and through raw requests. Environments side by side change only themselves. A metronome keeps running
 * while lamps are deployed, pressed and stopped around it. Concurrent workers churn through deployments with distinct
 * keys and no protocol violation. Environment keys stay unique for one client and for independent clients.
 * Environments that fail leave their neighbours reacting. The failures are a rejected upload, an attachment to an
 * unknown object and a reference loop.
 */
abstract class MultiEnvironmentScenarios {

    /** The runtime the scenarios deploy to. */
    protected abstract val runtime: SessionUnderTest

    /** The folder the scenarios copy their models into. */
    @TempDir
    lateinit var root: Path

    @Test
    fun `environments side by side change only themselves`() = runtime.run(root) {
        val deployed = coroutineScope {
            listOf(SynthesizedModel.LAMP, SynthesizedModel.LAMP, SynthesizedModel.METRONOME, SynthesizedModel.DEPOT)
                .map { model -> async { deploy(model, within = patience.crowd) } }
                .awaitAll()
        }
        val (lampOne, lampTwo, metronome, depot) = deployed

        coroutineScope {
            launch {
                metronome.awaitStack(METRONOME, REST, patience.crowd)
                metronome.inject("beat", "go")
                metronome.awaitModel("one burst", patience.crowd) { beatsOf(it.mirror) == BURST && it.stacks[METRONOME] == REST }
            }
            launch {
                depot.awaitStack(DEPOT, IDLE, patience.crowd)
                depot.inject("dock", "scrap")
                depot.awaitModel("the scrapped crate", patience.crowd) { it.mirror.objects[DEPOT]?.text("status") == "scrapped" }
            }
            press(lampTwo, ON, patience.crowd)
            press(lampOne, ON, patience.crowd)
        }

        assertEquals(listOf("off", "on"), lampOne.snapshot().mirror.objectOf("lamp").texts("log"))
        assertEquals(listOf("off", "on"), lampTwo.snapshot().mirror.objectOf("lamp").texts("log"))
        assertEquals(BURST, beatsOf(metronome.snapshot().mirror))
        val scrapped = depot.snapshot().mirror.objectOf(DEPOT)
        assertEquals("scrapped", scrapped.text("status"))
        assertTrue(scrapped.relations.first { it.key == "slot" }.targets.none { it is ObjectRef.Keyed }, "$scrapped")
    }

    @Test
    fun `a metronome keeps running while lamps are deployed, pressed and stopped around it`() = runtime.run(root) {
        val metronome = deploy(SynthesizedModel.METRONOME)
        var bursts = 0
        suspend fun burst() {
            metronome.awaitStack(METRONOME, REST, patience.burst)
            metronome.inject("beat", "go")
            bursts++
            metronome.awaitModel("burst $bursts", patience.burst) { beatsOf(it.mirror).size == bursts * BURST.size && it.stacks[METRONOME] == REST }
        }
        val lamps = ArrayList<EnvironmentKey>()

        repeat(ROUNDS) {
            val lamp = deploy(SynthesizedModel.LAMP, within = patience.crowd)
            lamps += lamp.environment
            burst()
            press(lamp, ON)
            burst()
            assertEquals(SessionCommandOutcome.Done, lamp.stop())
            burst()
        }

        assertEquals(List(bursts) { BURST }.flatten(), beatsOf(metronome.pulled().mirror))
        val listed = listing().map { it.key }
        assertTrue(listed.containsAll(lamps + metronome.environment), "listed $listed, deployed ${lamps + metronome.environment}")
    }

    @Test
    fun `concurrent workers churn through deployments with distinct keys and no protocol violation`() = runtime.run(root) {
        val sessions = coroutineScope {
            (1..WORKERS).map {
                async {
                    (1..ROUNDS + 1).map {
                        val lamp = deploy(SynthesizedModel.LAMP, within = patience.crowd)
                        press(lamp, ON, patience.crowd)
                        assertEquals(SessionCommandOutcome.Done, lamp.stop())
                        lamp
                    }
                }
            }.awaitAll().flatten()
        }

        assertEquals(WORKERS * (ROUNDS + 1), sessions.map { it.environment }.toSet().size)
        assertEquals(RuntimeResult.Ok(Unit), client.health())
        val violations = sessions.flatMap { it.journal() }.filterIsInstance<SessionActivity.Anomaly>().filter { "breaks the protocol" in it.detail }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `environment keys stay unique for one client, and a metamodel reaches only its own environment`() = runtime.run(root) {
        val created = coroutineScope { (1..ONE_CLIENT_CREATES).map { async { created(client) } }.awaitAll() }

        assertEquals(ONE_CLIENT_CREATES, created.map { it.key }.toSet().size)
        assertEquals(CallOutcome.Done(Unit), created.first().setMetamodel(SynthesizedModels.text(SynthesizedModel.LAMP, "lamp.kmeta")))
        created.drop(1).take(SAMPLE).forEach { other ->
            val answer = call(RuntimeRoute.SET_MODEL, "envKey" to other.key.value, body = SynthesizedModels.text(SynthesizedModel.LAMP, "lamp.kmodel"))
            assertEquals(CONFLICT to RuntimeText.METAMODEL_REQUIRED.render(mapOf("env" to other.key.value)), answer.status to answer.body)
        }
    }

    @Test
    fun `environment keys stay unique for independent clients creating at once`() = runtime.run(root) {
        val clients = (1..INDEPENDENT_CLIENTS).map { RuntimeClient(endpoint, clientOptions, ktorTransports(endpoint)) }
        try {
            val keys = coroutineScope {
                clients.map { independent -> async { (1..CREATES_PER_CLIENT).map { async { created(independent).key } }.awaitAll() } }.awaitAll().flatten()
            }

            assertEquals(INDEPENDENT_CLIENTS * CREATES_PER_CLIENT, keys.toSet().size)
        } finally {
            clients.forEach { it.close() }
        }
    }

    @Test
    fun `environments that fail leave their neighbours reacting`() = runtime.run(root) {
        val metronome = deploy(SynthesizedModel.METRONOME)
        val lamp = deploy(SynthesizedModel.LAMP)
        var round = 0
        suspend fun neighboursReact() {
            round++
            metronome.awaitStack(METRONOME, REST, patience.burst)
            metronome.inject("beat", "go")
            metronome.awaitModel("burst $round", patience.burst) { beatsOf(it.mirror).size == round * BURST.size && it.stacks[METRONOME] == REST }
            press(lamp, if (round % 2 == 1) ON else OFF)
            assertEquals(RuntimeResult.Ok(Unit), client.health())
            assertTrue(listing().map { it.key }.containsAll(listOf(metronome.environment, lamp.environment)), diagnostics)
        }

        val rejected = rawEnvironment()
        upload(rejected, RuntimeRoute.SET_METAMODEL, SynthesizedModel.LAMP, "lamp.kmeta")
        val refused = call(RuntimeRoute.SET_MODEL, "envKey" to rejected, body = SynthesizedModels.text(SynthesizedModel.LAMP, SynthesizedModels.LAMP_UNKNOWN_TYPE))
        assertEquals(BAD_REQUEST to "Unknown class type 'Bulbb' for object 'spare'", refused.status to refused.body)
        neighboursReact()

        val ghost = rawEnvironment()
        upload(ghost, RuntimeRoute.SET_METAMODEL, SynthesizedModel.LAMP, "lamp.kmeta")
        upload(ghost, RuntimeRoute.SET_EVENT_DEFINITIONS, SynthesizedModel.LAMP, "lamp-events.kmeta")
        upload(ghost, RuntimeRoute.SET_MODEL, SynthesizedModel.LAMP, "lamp.kmodel")
        upload(ghost, RuntimeRoute.SET_STATE_MACHINE, SynthesizedModel.LAMP, "lamp.kstates", "attachedTo" to "ghost")
        val run = call(RuntimeRoute.RUN_ENVIRONMENT, "envKey" to ghost)
        assertEquals(CONFLICT to RuntimeText.UNKNOWN_ATTACHMENT.render(mapOf("env" to ghost, "keys" to "ghost")), run.status to run.body)
        assertTrue("\"phase\":\"configuring\"" in call(RuntimeRoute.MODEL_SNAPSHOT, "envKey" to ghost).body, "the ghost stays configuring")
        neighboursReact()

        val ring = rawEnvironment()
        upload(ring, RuntimeRoute.SET_METAMODEL, SynthesizedModel.RING, "ring.kmeta")
        upload(ring, RuntimeRoute.SET_MODEL, SynthesizedModel.RING, "ring.kmodel")
        upload(ring, RuntimeRoute.SET_STATE_MACHINE, SynthesizedModel.RING, "ring.kstates", "attachedTo" to "a")
        val observer = rawObserver(EnvironmentKey(ring))
        assertEquals(OK, call(RuntimeRoute.RUN_ENVIRONMENT, "envKey" to ring).status)
        assertEquals(OK, call(RuntimeRoute.START_ENVIRONMENT, "envKey" to ring).status)
        eventually("the ring's engine reporting its stack") {
            observer.pushes().filterIsInstance<RuntimePush.StateChanged>().firstOrNull { it.modelElementId == "a" && it.stack == listOf("visit") }
        }
        val loop = call(RuntimeRoute.MODEL_SNAPSHOT, "envKey" to ring)
        assertEquals(CONFLICT to RuntimeText.REFERENCE_LOOP.render(mapOf("id" to "a")), loop.status to loop.body)
        neighboursReact()

        assertEquals(OK, call(RuntimeRoute.STOP_ENVIRONMENT, "envKey" to ring).status)
        neighboursReact()
        observer.close()
    }

    /**
     * Presses the lamp of [session] once its machine is observed in the other state, and waits, at most [within], for
     * the lamp's machine to show [stack].
     */
    private suspend fun SessionScenario.press(session: RuntimeSession, stack: List<String>, within: Duration = patience.reaction) {
        val before = if (stack == ON) OFF else ON
        session.awaitStack("lamp", before, within)
        session.inject("switch", "press")
        session.awaitStack("lamp", stack, within)
    }

    /** Creates an environment through [client] and returns its handle. It fails unless the environment was created. */
    private suspend fun SessionScenario.created(client: RuntimeClient): RuntimeEnvironment =
        assertIs<RuntimeResult.Ok<RuntimeEnvironment>>(client.createEnvironment(), diagnostics).value

    /** Creates an environment with a raw request and returns its key. */
    private suspend fun SessionScenario.rawEnvironment(): String {
        val answer = call(RuntimeRoute.CREATE_ENVIRONMENT)
        assertEquals(OK, answer.status, diagnostics)
        return answer.body
    }

    /**
     * Uploads the file [name] of [model] to [environment] with [route] and the extra [parameters]. It fails unless the
     * runtime accepts the upload.
     */
    private suspend fun SessionScenario.upload(
        environment: String,
        route: RuntimeRoute,
        model: SynthesizedModel,
        name: String,
        vararg parameters: Pair<String, String>,
    ) {
        val answer = call(route, "envKey" to environment, *parameters, body = SynthesizedModels.text(model, name))
        assertEquals(OK to "", answer.status to answer.body, "$route of $name to $environment")
    }

    /** The environments the runtime lists. It fails unless the runtime answers. */
    private suspend fun SessionScenario.listing(): List<ActiveEnvironment> =
        assertIs<RuntimeResult.Ok<List<ActiveEnvironment>>>(client.activeEnvironments(), diagnostics).value

    private companion object {
        /** The metronome's key. */
        const val METRONOME = "metronome"

        /** The depot's key. */
        const val DEPOT = "depot"

        /** The metronome's stack at rest. */
        val REST = listOf("rest")

        /** The depot's stack while it waits. */
        val IDLE = listOf("idle")

        /** The lamp's stack while it is on. */
        val ON = listOf("on")

        /** The lamp's stack while it is off. */
        val OFF = listOf("off")

        /** The beats one burst appends. */
        val BURST = listOf("1", "2", "3", "a", "b")

        /** The rounds of the staggered lifecycles. */
        const val ROUNDS = 3

        /** The concurrent workers of the churn. */
        const val WORKERS = 3

        /** The environments one client creates at once. */
        const val ONE_CLIENT_CREATES = 30

        /** How many of them are checked for the metamodel of another. */
        const val SAMPLE = 5

        /** The independent clients. */
        const val INDEPENDENT_CLIENTS = 4

        /** The environments each independent client creates. */
        const val CREATES_PER_CLIENT = 15

        /** The status of a success. */
        const val OK = 200

        /** The status of a rejected argument. */
        const val BAD_REQUEST = 400

        /** The status of a rejected state. */
        const val CONFLICT = 409

        /** The beats of the metronome in [mirror]. There are none before the first burst. */
        fun beatsOf(mirror: MirrorSnapshot): List<String> = mirror.objects[METRONOME]?.texts("beats").orEmpty()
    }
}
