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

import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.connection.ReconnectPolicy
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.session.events.InjectionOutcome
import io.karpfen.ide.runtime.session.events.InjectionRequest
import io.karpfen.ide.runtime.session.events.PayloadKind
import io.karpfen.ide.runtime.session.observation.ModelUpdate
import io.karpfen.ide.runtime.session.observation.ObservationMode
import io.karpfen.ide.runtime.session.observation.SyncReason
import io.karpfen.ide.runtime.session.observation.SyncStatus
import io.karpfen.ide.runtime.session.observation.UpdateCause
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers what a running session observes, end to end against the fake runtime, which answers only what each test
 * programs: object and stack pushes reach the observed model, the states and the model updates in revision order;
 * injected events leave as the exact frames the runtime reads; observation switched off ignores pushes and switched on
 * reads a snapshot first; a dropped link reconnects with a fresh key, a second epoch and a re-synchronization; a lost
 * push shows as a revision gap and is healed by a snapshot; domain events flow whatever the switch; and a pull applies
 * a snapshot, and while the link is down it leaves the model synchronizing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeSessionObservationTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `pushes reach the observed model, the states and the model updates in revision order`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        val session = rig.running(deployment)
        val updates = ArrayList<ModelUpdate>()
        backgroundScope.launch { session.modelUpdates.collect { updates += it } }
        runCurrent()
        val link = rig.linkOf(session)

        link.push(rig.stack(session, "lamp", listOf("on"), 3))
        link.push(rig.changed(session, "lamp", deployment.json("lamp", SessionRig.text("mode", "on")), 4))
        link.push(rig.stack(session, "wallSwitch", listOf("up"), 5))
        runCurrent()

        val model = session.model.value
        assertEquals(SyncStatus.Live(5), model.sync)
        assertEquals(mapOf("lamp" to listOf("on"), "wallSwitch" to listOf("up")), model.stacks)
        assertEquals("on", model.mirror.objectOf("lamp").text("mode"))
        assertEquals(listOf(3L, 4L, 5L), updates.map { it.revision })
        assertTrue(updates.all { it.cause == UpdateCause.PUSH }, "$updates")
        assertEquals(setOf("lamp"), updates[1].changed)
        val applied = session.journal().filterIsInstance<SessionActivity.PushApplied>()
        assertEquals(listOf("stateChange" to 3L, "objectChanged" to 4L, "stateChange" to 5L), applied.map { it.messageType to it.revision })
        session.close()
    }

    @Test
    fun `an injected event leaves as the exact frame the runtime reads`() = runTest {
        val rig = SessionRig(this, root)
        val session = rig.running(rig.prepared(SynthesizedModel.LAMP))

        val press = session.events.inject(InjectionRequest("switch", "press", PayloadKind.NONE, ""))
        val dim = session.events.inject(InjectionRequest("switch", "dim", PayloadKind.JSON, """{"bright": true}"""))

        val pressFrame = """{"environmentKey":"switch","messageType":"press","payload":"","payloadFormat":"none"}"""
        val dimFrame = """{"environmentKey":"switch","messageType":"dim","payload":"{\"bright\": true}","payloadFormat":"json"}"""
        assertEquals(pressFrame, assertIs<InjectionOutcome.Sent>(press).frameText)
        assertEquals(dimFrame, assertIs<InjectionOutcome.Sent>(dim).frameText)
        assertEquals(listOf(pressFrame, dimFrame), rig.fake.clientFrames)
        runCurrent()
        assertEquals(listOf(pressFrame, dimFrame), session.journal().filterIsInstance<SessionActivity.EventSent>().map { it.frameText })
        session.close()
    }

    @Test
    fun `observation off ignores pushes and observation on reads a snapshot before it applies pushes again`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        val session = rig.running(deployment)
        val link = rig.linkOf(session)

        assertEquals(SessionCommandOutcome.Done, session.setObservation(false))
        link.push(rig.stack(session, "lamp", listOf("on"), 3))
        runCurrent()

        assertEquals(1, rig.fake.requests(RuntimeRoute.UNSUBSCRIBE_MODEL).size)
        assertEquals(ObservationMode.OFF, session.model.value.mode)
        assertEquals(SyncStatus.Pulled(2), session.model.value.sync)
        assertEquals(listOf("off"), session.model.value.stacks["lamp"], "a push while off is ignored")

        rig.programSnapshot(deployment, "env-1", RuntimePhase.RUNNING, 3, mapOf("lamp" to listOf("on"), "wallSwitch" to listOf("down")))
        assertEquals(SessionCommandOutcome.Done, session.setObservation(true))
        link.push(rig.stack(session, "wallSwitch", listOf("up"), 4))
        runCurrent()

        assertEquals(2, rig.fake.requests(RuntimeRoute.SUBSCRIBE_MODEL).size)
        assertEquals(SyncStatus.Live(4), session.model.value.sync)
        assertEquals(mapOf("lamp" to listOf("on"), "wallSwitch" to listOf("up")), session.model.value.stacks)
        assertTrue(
            session.journal().any { it is SessionActivity.Synchronized && it.reason == SyncReason.SWITCHED_ON && it.revision == 3L },
            "${session.journal()}",
        )
        session.close()
    }

    @Test
    fun `a dropped link reconnects with a fresh key in a second epoch and re-synchronizes the model`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        val session = rig.running(deployment)
        val keysBefore = rig.fake.requests(RuntimeRoute.REGISTER_CLIENT_FOR_WEB_SOCKET).size
        rig.programSnapshot(deployment, "env-1", RuntimePhase.RUNNING, 7, mapOf("lamp" to listOf("on"), "wallSwitch" to listOf("up")))

        rig.linkOf(session).drop()
        advanceTimeBy(ReconnectPolicy().delayBefore(1) + 1.seconds)

        assertEquals(ConnectionState.Open(2), session.connection.value)
        assertEquals(keysBefore + 1, rig.fake.requests(RuntimeRoute.REGISTER_CLIENT_FOR_WEB_SOCKET).size, "a fresh key")
        assertEquals(2, rig.fake.links.size)
        assertEquals(2, rig.fake.requests(RuntimeRoute.SUBSCRIBE_MODEL).size, "subscribed again on the new link")
        assertEquals(SyncStatus.Live(7), session.model.value.sync)
        assertEquals(mapOf("lamp" to listOf("on"), "wallSwitch" to listOf("up")), session.model.value.stacks)
        assertTrue(
            session.journal().any { it is SessionActivity.Synchronized && it.reason == SyncReason.RECONNECTED && it.revision == 7L },
            "${session.journal()}",
        )
        session.close()
    }

    @Test
    fun `a push lost by the runtime shows as a gap, and a snapshot brings the model live again`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.METRONOME)
        val session = rig.running(deployment)
        val link = rig.linkOf(session)
        val healed = deployment.json("metronome", SessionRig.text("phase", "beating"), SessionRig.texts("beats", "1", "2", "3"))
        rig.programSnapshot(deployment, "env-1", RuntimePhase.RUNNING, 5, mapOf("metronome" to listOf("beat three")), roots = listOf(healed))

        link.push(rig.stack(session, "metronome", listOf("beat one"), 2))
        link.push(rig.stack(session, "metronome", listOf("beat three"), 5))
        runCurrent()

        assertEquals(SyncStatus.Live(5), session.model.value.sync)
        assertEquals(listOf("beat three"), session.model.value.stacks["metronome"])
        assertEquals(listOf("1", "2", "3"), session.model.value.mirror.objectOf("metronome").texts("beats"))
        val journal = session.journal()
        assertTrue(journal.any { it is SessionActivity.Anomaly && "revisions 3 to 4" in it.detail }, "$journal")
        assertTrue(journal.any { it is SessionActivity.Synchronized && it.reason == SyncReason.GAP && it.revision == 5L }, "$journal")
        session.close()
    }

    @Test
    fun `domain events reach their flow and the journal whether observation is on or off`() = runTest {
        val rig = SessionRig(this, root)
        val session = rig.running(rig.prepared(SynthesizedModel.LAMP))
        val events = ArrayList<ObservedDomainEvent>()
        backgroundScope.launch { session.domainEvents.collect { events += it } }
        runCurrent()
        val link = rig.linkOf(session)

        link.push(rig.domainEvent(session, "switch", "press"))
        runCurrent()
        session.setObservation(false)
        link.push(rig.domainEvent(session, "switch", "press"))
        runCurrent()

        assertEquals(listOf("press", "press"), events.map { it.event?.name })
        assertTrue(events.all { it.domain == "switch" && it.event?.source == "EXTERNAL_MESSAGE" }, "$events")
        assertEquals(2, session.journal().count { it is SessionActivity.DomainEventReceived })
        session.close()
    }

    @Test
    fun `a pull while the link is down reads the model but leaves it synchronizing until the link opens again`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        val session = rig.running(deployment)
        rig.fake.hangConnect()
        rig.linkOf(session).drop()
        advanceTimeBy(ReconnectPolicy().delayBefore(1) + 1.seconds)
        assertIs<ConnectionState.Connecting>(session.connection.value)
        rig.programSnapshot(deployment, "env-1", RuntimePhase.RUNNING, 7, mapOf("lamp" to listOf("on"), "wallSwitch" to listOf("down")))

        assertEquals(SessionCommandOutcome.Done, session.pull())

        assertEquals(ObservationMode.ON, session.model.value.mode)
        assertEquals(SyncStatus.Synchronizing(SyncReason.RECONNECTED), session.model.value.sync)
        assertEquals(listOf("on"), session.model.value.stacks["lamp"], "the pulled model is shown")
        runCurrent()
        val journal = session.journal()
        assertTrue(journal.none { it is SessionActivity.Synchronized && it.revision == 7L }, "$journal")
        assertTrue(journal.any { it is SessionActivity.Note && it.text == "Read the model at revision 7." }, "$journal")
        session.close()
    }

    @Test
    fun `a pull applies a snapshot while observation is off`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        val session = rig.running(deployment)
        session.setObservation(false)
        rig.programSnapshot(
            deployment, "env-1", RuntimePhase.RUNNING, 9, mapOf("lamp" to listOf("on"), "wallSwitch" to listOf("up")),
            roots = listOf(deployment.json("living room")),
        )

        assertEquals(SessionCommandOutcome.Done, session.pull())

        assertEquals(SyncStatus.Pulled(9), session.model.value.sync)
        assertEquals(mapOf("lamp" to listOf("on"), "wallSwitch" to listOf("up")), session.model.value.stacks)
        session.close()
    }

}
