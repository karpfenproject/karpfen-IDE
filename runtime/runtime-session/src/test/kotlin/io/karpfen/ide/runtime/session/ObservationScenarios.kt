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

import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.awaitValue
import io.karpfen.ide.runtime.protocol.conformance.eventually
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.message.WireValue
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import io.karpfen.ide.runtime.session.events.DomainOrigin
import io.karpfen.ide.runtime.session.mirror.MirrorComparison
import io.karpfen.ide.runtime.session.mirror.MirrorSnapshot
import io.karpfen.ide.runtime.session.observation.ObservedModel
import io.karpfen.ide.runtime.session.observation.SyncStatus
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import io.karpfen.ide.runtime.session.states.StackOrigin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers observing the model through a session against a real runtime. The read-back of the lamp, the metronome and
 * the single robot equals the seed, and after the start only the initial entry blocks changed it. A pull while
 * observation is off shows the model of a snapshot. While observation is off, no object or stack push reaches the
 * session. Switching on in the middle of a burst neither loses nor reorders anything. Switching off and on again
 * converges every time. Observation belongs to one client. A stopped environment can still be pulled. Domain events
 * keep flowing while observation is off. The check that every burst raises an internal domain event waits for a fix of
 * the runtime. The runtime skips the entry block of a state that a join leaves in the next tick.
 *
 * Absence is proven by a cause, never by a time window. A revision range and an append-only list would show an unwanted
 * push by their lengths. They are checked after a later burst on the same connection, which arrives after anything
 * earlier. A dropped push that heals through a revision gap is a failure that only a fake can produce. It is an
 * ordinary test against the fake runtime.
 */
abstract class ObservationScenarios {

    /** The runtime the scenarios deploy to. */
    protected abstract val runtime: SessionUnderTest

    /** The folder the scenarios copy their models into. */
    @TempDir
    lateinit var root: Path

    @Test
    fun `the read-back equals the seed, and after the start only the initial entry blocks changed the model`() = runtime.run(root) {
        val lamp = deploy(SynthesizedModel.LAMP, options = DeployOptions(start = false))
        val metronome = deploy(SynthesizedModel.METRONOME, options = DeployOptions(start = false))
        val robot = deploySingleRobot(DeployOptions(start = false))
        val sessions = listOf(lamp, metronome, robot)

        sessions.forEach { session ->
            assertTrue(session.problems.value.none { it.code == DeployProblemCodes.MODEL_DIFFERS }, "${session.problems.value}")
            assertEquals(emptyList(), MirrorComparison.differences(session.prepared.seed, session.model.value.mirror), diagnostics)
        }
        sessions.forEach { session ->
            assertEquals(SessionCommandOutcome.Done, session.start())
            session.awaitModel("every machine reported") { model -> model.states.machines.values.all { it.origin == StackOrigin.REPORTED } }
        }

        val lampModel = lamp.pulled().mirror
        assertEquals(listOf("lamp"), changedSinceSeed(lamp, lampModel))
        assertEquals(listOf("off"), lampModel.objectOf("lamp").texts("log"))
        val metronomeModel = metronome.pulled().mirror
        assertEquals(listOf("metronome"), changedSinceSeed(metronome, metronomeModel))
        assertEquals("rest", metronomeModel.objectOf("metronome").text("phase"))
        assertEquals(emptyList(), changedSinceSeed(robot, robot.pulled().mirror))
    }

    @Test
    fun `a pull while observation is off shows the model of a snapshot`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.METRONOME)
        session.awaitStack(METRONOME, REST)
        assertEquals(SessionCommandOutcome.Done, session.setObservation(false))

        session.inject("beat", "go")
        val burst = burstDone(session, 5)

        val pulled = session.pulled()
        assertEquals(SyncStatus.Pulled(burst.revision), pulled.sync)
        assertEquals(emptyList(), MirrorComparison.differences(burst.mirror, pulled.mirror), diagnostics)
    }

    @Test
    fun `while observation is off no object or stack push reaches the session`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.METRONOME)
        val start = eventually("the mirror equal to a snapshot at rest") {
            session.snapshot().takeIf { it.stacks[METRONOME] == REST && differences(it, session.model.value).isEmpty() }
        }
        val before = beatsOf(start.mirror).size

        assertEquals(SessionCommandOutcome.Done, session.setObservation(false))
        val switchedOff = assertIs<SyncStatus.Pulled>(session.pulled().sync).revision
        session.inject("beat", "go")
        val whileOff = burstDone(session, before + 5).revision
        assertEquals(SessionCommandOutcome.Done, session.setObservation(true))
        session.inject("beat", "go")
        session.awaitModel("the burst after switching on", patience.burst) { beatsOf(it.mirror).size == before + 10 && it.stacks[METRONOME] == REST }

        val pushes = session.received()
        assertEquals(emptyList(), pushes.filter { revisionOf(it) in (switchedOff + 1)..whileOff }, "pushes of the burst while off. $diagnostics")
        val lengths = pushes.filterIsInstance<RuntimePush.ObjectChanged>().filter { it.objectId == METRONOME }.map { beatsLength(it.value) }
        assertTrue(lengths.none { it in (before + 1)..(before + 5) }, "beats lengths of the pushes: $lengths. $diagnostics")
    }

    @Test
    fun `switching on in the middle of a burst neither loses nor reorders anything`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.METRONOME)
        session.awaitStack(METRONOME, REST)
        assertEquals(SessionCommandOutcome.Done, session.setObservation(false))
        val lengths = CopyOnWriteArrayList<Int>()
        launch(start = CoroutineStart.UNDISPATCHED) { session.model.collect { lengths += beatsOf(it.mirror).size } }

        session.inject("beat", "go")
        assertEquals(SessionCommandOutcome.Done, session.setObservation(true))
        burstDone(session, 5)
        eventually("the mirror equal to a fresh snapshot") { session.snapshot().takeIf { differences(it, session.model.value).isEmpty() } }

        assertTrue(lengths.zipWithNext().all { (earlier, later) -> earlier <= later }, "the beats lengths the mirror showed: $lengths")
        assertEquals(BURST, beatsOf(session.model.value.mirror))
    }

    @Test
    fun `switching observation off and on again converges every time`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.METRONOME)
        session.awaitStack(METRONOME, REST)

        repeat(ROUNDS) { round ->
            val before = round * 10
            assertEquals(SessionCommandOutcome.Done, session.setObservation(false))
            session.inject("beat", "go")
            burstDone(session, before + 5)
            assertEquals(SessionCommandOutcome.Done, session.setObservation(true))
            session.awaitModel("the burst while off in the mirror") { beatsOf(it.mirror).size == before + 5 && it.stacks[METRONOME] == REST }
            session.inject("beat", "go")
            session.awaitModel("the burst while on", patience.burst) { beatsOf(it.mirror).size == before + 10 && it.stacks[METRONOME] == REST }
            eventually<RawSnapshot>("the mirror equal to a fresh snapshot after round ${round + 1}") {
                session.snapshot().takeIf { differences(it, session.model.value).isEmpty() }
            }
        }

        assertEquals(List(ROUNDS * 2) { BURST }.flatten(), beatsOf(session.model.value.mirror))
    }

    @Test
    fun `observation belongs to one client`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.METRONOME)
        session.awaitStack(METRONOME, REST)
        val other = rawObserver(session.environment)

        assertEquals(SessionCommandOutcome.Done, session.setObservation(false))
        val switchedOff = assertIs<SyncStatus.Pulled>(session.pulled().sync).revision
        session.inject("beat", "go")
        val whileOff = burstDone(session, 5).revision
        eventually("the burst's last push at the other client") {
            other.pushes().filterIsInstance<RuntimePush.ObjectChanged>().firstOrNull { it.objectId == METRONOME && beatsLength(it.value) == 5 }
        }
        assertEquals(SessionCommandOutcome.Done, session.setObservation(true))
        session.inject("beat", "go")
        session.awaitModel("the next burst at the session", patience.burst) { beatsOf(it.mirror).size == 10 && it.stacks[METRONOME] == REST }

        val burst = (switchedOff + 1)..whileOff
        assertTrue(other.pushes().any { revisionOf(it) in burst }, "the other client received the burst. $diagnostics")
        assertEquals(emptyList(), session.received().filter { revisionOf(it) in burst }, "pushes of the burst at the session. $diagnostics")
        other.close()
    }

    @Test
    fun `a stopped environment can still be pulled`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.LAMP)

        assertEquals(SessionCommandOutcome.Done, session.stop())
        val pulled = session.pulled()

        assertEquals(RuntimePhase.STOPPED, pulled.phase)
        assertIs<SyncStatus.Final>(pulled.sync)
    }

    @Test
    fun `domain events keep flowing while observation is off`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.METRONOME)
        val announced = MutableStateFlow<List<ObservedDomainEvent>>(emptyList())
        launch(start = CoroutineStart.UNDISPATCHED) { session.domainEvents.collect { event -> announced.update { it + event } } }
        session.awaitStack(METRONOME, REST)
        assertEquals(SessionCommandOutcome.Done, session.setObservation(false))

        repeat(2) { burst ->
            session.inject("beat", "go")
            burstDone(session, (burst + 1) * 5)
        }

        val go = awaitValue("both goes announced while observation is off", announced) { events -> events.count { it.event?.name == "go" } >= 2 }
        assertTrue(go.filter { it.event?.name == "go" }.all { it.domain == "beat" && it.event?.source == "EXTERNAL_MESSAGE" }, "$go")
        assertEquals(DomainOrigin.DECLARED, session.events.catalog.domain("beat")?.origin)
        assertEquals(DomainOrigin.USED_ONLY, session.events.catalog.domain("tick")?.origin)
    }

    @Test
    @Disabled(
        "The runtime skips the entry block of a state that a join leaves in the next tick, so the metronome's 'lane b " +
            "done' never raises 'burst done'; reported to the owner as a runtime bug, and the scenario runs again once " +
            "the runtime enters such states before the join fires.",
    )
    fun `every burst raises one internal burst done event, also while observation is off`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.METRONOME)
        val announced = MutableStateFlow<List<ObservedDomainEvent>>(emptyList())
        launch(start = CoroutineStart.UNDISPATCHED) { session.domainEvents.collect { event -> announced.update { it + event } } }
        session.awaitStack(METRONOME, REST)
        assertEquals(SessionCommandOutcome.Done, session.setObservation(false))

        repeat(2) { burst ->
            session.inject("beat", "go")
            burstDone(session, (burst + 1) * 5)
        }

        val done = awaitValue("one burst done per burst", announced) { events -> events.count { it.event?.name == "burst done" } >= 2 }
        assertTrue(done.filter { it.event?.name == "burst done" }.all { it.domain == "tick" && it.event?.source == "INTERNAL" }, "$done")
    }

    /**
     * Polls raw snapshots of [session]'s metronome until a burst is done and the metronome is at rest with [beats]
     * beats. Returns that snapshot.
     */
    private suspend fun SessionScenario.burstDone(session: RuntimeSession, beats: Int): RawSnapshot =
        eventually("the metronome at rest with $beats beats", patience.burst) {
            session.snapshot().takeIf { snapshot ->
                snapshot.stacks[METRONOME] == REST && beatsOf(snapshot.mirror).size == beats &&
                    snapshot.mirror.objectOf(METRONOME).text("phase") == "rest"
            }
        }

    /** The keyed objects of [mirror] that differ from the seed of [session]. */
    private fun changedSinceSeed(session: RuntimeSession, mirror: MirrorSnapshot): List<String> =
        MirrorComparison.differences(session.prepared.seed, mirror).map { it.id }

    /** How [model]'s mirror differs from [snapshot]'s. */
    private fun differences(snapshot: RawSnapshot, model: ObservedModel) = MirrorComparison.differences(snapshot.mirror, model.mirror)

    private companion object {
        /** The metronome's key. */
        const val METRONOME = "metronome"

        /** The metronome's stack at rest. */
        val REST = listOf("rest")

        /** The beats one burst appends. */
        val BURST = listOf("1", "2", "3", "a", "b")

        /** The rounds of switching off and on. */
        const val ROUNDS = 3

        /** The beats of the metronome in [mirror]. There are none before the first burst. */
        fun beatsOf(mirror: MirrorSnapshot): List<String> = mirror.objects[METRONOME]?.texts("beats").orEmpty()

        /** The number of beats in the pushed value of the metronome. */
        fun beatsLength(value: WireValue.WireObject): Int = (value["beats"] as? WireValue.WireArray)?.items?.size ?: 0

        /** The revision of an object or stack push. It is 0 for a domain event or an unknown message. */
        fun revisionOf(push: RuntimePush): Long =
            when (push) {
                is RuntimePush.ObjectChanged -> push.revision
                is RuntimePush.ObjectDeleted -> push.revision
                is RuntimePush.StateChanged -> push.revision
                is RuntimePush.DomainEvent, is RuntimePush.EngineStatusChanged, is RuntimePush.Unrecognized -> 0
            }
    }
}
