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
package io.karpfen.ide.runtime.session.observation

import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.message.PublishedEvent
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.message.WireValue
import io.karpfen.ide.runtime.session.examples.ReadModel
import io.karpfen.ide.runtime.session.examples.WireTexts
import io.karpfen.ide.runtime.session.mirror.PropertyValue
import io.karpfen.ide.runtime.session.mirror.ScalarValue
import io.karpfen.ide.runtime.session.states.ActiveStatesReducer
import io.karpfen.ide.runtime.session.states.MachineLayout
import io.karpfen.ide.runtime.session.states.StackOrigin
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the rules of the observation switch row by row: switching on and off, pushes while off, while synchronizing
 * and while live (dropped, applied, a gap), snapshots read and failed, a reconnect, an interruption, snapshots while
 * the link is down and the stop.
 */
class ObservationReducerTest {

    private val lamp = ReadModel.synthesized(SynthesizedModel.LAMP)

    private val reducer = ObservationReducer(lamp.reducer(), ActiveStatesReducer(listOf(LAMP, SWITCH)))

    @Test
    fun `before anything is read the model is the seed with predicted states, observation off`() {
        val state = reducer.initial(lamp.seed)

        assertEquals(ObservationMode.OFF, state.model.mode)
        assertEquals(SyncStatus.Predicted, state.model.sync)
        assertNull(state.model.phase)
        assertSame(lamp.seed, state.model.mirror)
        assertEquals(StackOrigin.PREDICTED, state.model.states.machines.getValue("lamp").origin)
        assertEquals(0L, state.lastRevision)
    }

    @Test
    fun `switched on, the observation synchronizes and asks for a snapshot`() {
        val step = reducer.apply(reducer.initial(lamp.seed), ObservationInput.SwitchedOn)

        assertEquals(ObservationMode.ON, step.state.model.mode)
        assertEquals(SyncStatus.Synchronizing(SyncReason.SWITCHED_ON), step.state.model.sync)
        assertEquals(listOf(ObservationRequest.ReadSnapshot), step.requests)
        assertNull(step.update)
    }

    @Test
    fun `switched off, the observation shows the last revision as pulled and keeps the mirror`() {
        val live = applied(live(at = 5), bulb(6, watts = 60))

        val step = reducer.apply(live, ObservationInput.SwitchedOff)

        assertEquals(ObservationMode.OFF, step.state.model.mode)
        assertEquals(SyncStatus.Pulled(6), step.state.model.sync)
        assertSame(live.model.mirror, step.state.model.mirror)
        assertEquals(emptyList(), step.requests)
        assertEquals(
            SyncStatus.Predicted,
            reducer.apply(reducer.apply(reducer.initial(lamp.seed), ObservationInput.SwitchedOn).state, ObservationInput.SwitchedOff).state.model.sync,
        )
    }

    @Test
    fun `an object or stack push while observation is off is ignored`() {
        val off = reducer.apply(live(at = 5), ObservationInput.SwitchedOff).state

        listOf(bulb(6, watts = 60), RuntimePush.StateChanged("lamp", listOf("on"), 6), RuntimePush.ObjectDeleted("spare", 6))
            .forEach { push -> assertUnchanged(off, ObservationInput.Pushed(push)) }
    }

    @Test
    fun `an object or stack push while synchronizing is ignored, because the pending snapshot covers it`() {
        val synchronizing = reducer.apply(reducer.initial(lamp.seed), ObservationInput.SwitchedOn).state

        assertUnchanged(synchronizing, ObservationInput.Pushed(bulb(1, watts = 60)))
        assertUnchanged(synchronizing, ObservationInput.Pushed(RuntimePush.StateChanged("lamp", listOf("on"), 1)))
    }

    @Test
    fun `while live, pushes at or below the last revision are ignored`() {
        val live = live(at = 5)

        assertUnchanged(live, ObservationInput.Pushed(bulb(5, watts = 60)))
        assertUnchanged(live, ObservationInput.Pushed(bulb(3, watts = 60)))
        assertUnchanged(live, ObservationInput.Pushed(RuntimePush.ObjectDeleted("spare", 4)))
    }

    @Test
    fun `while live, the next object push is applied and becomes an update`() {
        val step = reducer.apply(live(at = 5), ObservationInput.Pushed(bulb(6, watts = 60)))

        assertEquals(SyncStatus.Live(6), step.state.model.sync)
        assertEquals(6L, step.state.lastRevision)
        assertEquals(ModelUpdate(6, setOf("bulb"), emptySet(), emptySet(), emptySet(), UpdateCause.PUSH), step.update)
        assertEquals(
            PropertyValue.Single(ScalarValue.Number(60.0)),
            step.state.model.mirror.objects.getValue("bulb").property("watts")?.value,
        )
        assertEquals(emptyList(), step.requests)
    }

    @Test
    fun `while live, the next deletion and the next stack push are applied`() {
        val deleted = reducer.apply(live(at = 5), ObservationInput.Pushed(RuntimePush.ObjectDeleted("spare", 6)))
        val stacked = reducer.apply(deleted.state, ObservationInput.Pushed(RuntimePush.StateChanged("lamp", listOf("on"), 7)))

        assertEquals(ModelUpdate(6, setOf("lamp"), emptySet(), setOf("spare"), emptySet(), UpdateCause.PUSH), deleted.update)
        assertEquals(ModelUpdate(7, emptySet(), emptySet(), emptySet(), setOf("lamp"), UpdateCause.PUSH), stacked.update)
        assertEquals(listOf("on"), stacked.state.model.states.machines.getValue("lamp").stack)
        assertEquals(SyncStatus.Live(7), stacked.state.model.sync)
    }

    @Test
    fun `while live, a push that skips revisions is a gap that asks for a snapshot`() {
        val live = live(at = 5)

        val gap = reducer.apply(live, ObservationInput.Pushed(bulb(8, watts = 60)))
        val single = reducer.apply(live, ObservationInput.Pushed(RuntimePush.StateChanged("lamp", listOf("on"), 7)))

        assertEquals(SyncStatus.Synchronizing(SyncReason.GAP), gap.state.model.sync)
        assertEquals(listOf(ObservationRequest.ReadSnapshot), gap.requests)
        assertSame(live.model.mirror, gap.state.model.mirror)
        assertNull(gap.update)
        assertEquals(listOf("A push with revision 8 arrived after revision 5: revisions 6 to 7 did not arrive."), gap.anomalies)
        assertEquals(listOf("A push with revision 7 arrived after revision 5: revision 6 did not arrive."), single.anomalies)
    }

    @Test
    fun `a snapshot read while on replaces mirror and states, sets the phase and goes live with one update of what differs`() {
        val synchronizing = reducer.apply(reducer.initial(lamp.seed), ObservationInput.SwitchedOn).state
        val roots = rootsWith(mode = "on")

        val step = reducer.apply(
            synchronizing,
            ObservationInput.SnapshotRead(snapshot(revision = 12, roots = roots, activeStates = mapOf("lamp" to listOf("on")))),
        )

        assertEquals(SyncStatus.Live(12), step.state.model.sync)
        assertEquals(RuntimePhase.RUNNING, step.state.model.phase)
        assertEquals(12L, step.state.lastRevision)
        assertEquals(ModelUpdate(12, setOf("lamp"), emptySet(), emptySet(), setOf("lamp"), UpdateCause.SNAPSHOT), step.update)
        assertEquals(StackOrigin.REPORTED, step.state.model.states.machines.getValue("lamp").origin)
        assertEquals(StackOrigin.PREDICTED, step.state.model.states.machines.getValue("wallSwitch").origin)
        assertEquals(12L, step.state.model.mirror.objects.getValue("lamp").revision)
        assertEquals(emptyList(), step.requests)
    }

    @Test
    fun `a snapshot read while off shows the model as pulled, and one read after the stop is final`() {
        val pulled = reducer.apply(reducer.initial(lamp.seed), ObservationInput.SnapshotRead(snapshot(revision = 3)))
        val stopped = reducer.apply(pulled.state, ObservationInput.EnvironmentStopped)
        val final = reducer.apply(stopped.state, ObservationInput.SnapshotRead(snapshot(revision = 9, phase = RuntimePhase.STOPPED)))

        assertEquals(SyncStatus.Pulled(3), pulled.state.model.sync)
        assertEquals(ModelUpdate(3, emptySet(), emptySet(), emptySet(), emptySet(), UpdateCause.SNAPSHOT), pulled.update)
        assertEquals(SyncStatus.Final(9), final.state.model.sync)
        assertEquals(RuntimePhase.STOPPED, final.state.model.phase)
        assertEquals(ObservationMode.OFF, final.state.model.mode)
    }

    @Test
    fun `a snapshot older than the last applied revision is ignored, one at the same revision is applied`() {
        val live = applied(live(at = 5), bulb(6, watts = 60))

        val older = reducer.apply(live, ObservationInput.SnapshotRead(snapshot(revision = 5)))
        val same = reducer.apply(live, ObservationInput.SnapshotRead(snapshot(revision = 6)))

        assertSame(live, older.state)
        assertNull(older.update)
        assertEquals(1, older.anomalies.size)
        assertEquals(SyncStatus.Live(6), same.state.model.sync)
        assertEquals(setOf("bulb"), same.update?.changed)
    }

    @Test
    fun `a snapshot lists created and deleted objects and the machines whose stack changed`() {
        val live = live(at = 5, activeStates = mapOf("lamp" to listOf("off"), "wallSwitch" to listOf("down")))
        val withoutSpare = listOf(WireTexts.wireObject(lamp.wire.rootsJson().single().replace(SPARE, GHOST_SPARE)))

        val step = reducer.apply(
            live,
            ObservationInput.SnapshotRead(snapshot(revision = 9, roots = withoutSpare, activeStates = mapOf("lamp" to listOf("off")))),
        )

        assertEquals(setOf("lamp"), step.update?.changed)
        assertEquals(setOf("gen_Bulb_1"), step.update?.created)
        assertEquals(setOf("spare"), step.update?.deleted)
        assertEquals(setOf("wallSwitch"), step.update?.machines)
    }

    @Test
    fun `a failed snapshot leaves observation synchronizing when on and changes nothing when off`() {
        val synchronizing = reducer.apply(reducer.initial(lamp.seed), ObservationInput.SwitchedOn).state

        val on = reducer.apply(synchronizing, ObservationInput.SnapshotFailed("no answer"))

        assertEquals(SyncStatus.Synchronizing(SyncReason.SNAPSHOT_FAILED), on.state.model.sync)
        assertEquals(emptyList(), on.requests)
        assertUnchanged(reducer.initial(lamp.seed), ObservationInput.SnapshotFailed("no answer"))
    }

    @Test
    fun `a reopened connection while on resubscribes and reads a snapshot, while the first open changes nothing`() {
        val live = live(at = 5)

        val reopened = reducer.apply(live, ObservationInput.LinkOpened(2))

        assertEquals(SyncStatus.Synchronizing(SyncReason.RECONNECTED), reopened.state.model.sync)
        assertEquals(listOf(ObservationRequest.Resubscribe, ObservationRequest.ReadSnapshot), reopened.requests)
        assertUnchanged(live, ObservationInput.LinkOpened(1))
        assertUnchanged(reducer.apply(live, ObservationInput.SwitchedOff).state, ObservationInput.LinkOpened(3))
    }

    @Test
    fun `an interrupted connection while on waits for the next open without requests`() {
        val live = live(at = 5)

        val interrupted = reducer.apply(live, ObservationInput.LinkInterrupted)

        assertEquals(SyncStatus.Synchronizing(SyncReason.RECONNECTED), interrupted.state.model.sync)
        assertEquals(emptyList(), interrupted.requests)
        assertTrue(interrupted.state.linkDown)
        val off = reducer.apply(live, ObservationInput.SwitchedOff).state
        val interruptedOff = reducer.apply(off, ObservationInput.LinkInterrupted)
        assertSame(off.model, interruptedOff.state.model, "the model stays as it is while off")
        assertTrue(interruptedOff.state.linkDown)
        assertEquals(emptyList(), interruptedOff.requests)
    }

    @Test
    fun `while the link is down a snapshot updates the model but never makes it live, until the read after the next open`() {
        val down = reducer.apply(live(at = 5), ObservationInput.LinkInterrupted).state

        val pulled = reducer.apply(down, ObservationInput.SnapshotRead(snapshot(revision = 7, roots = rootsWith("on"))))

        assertEquals(SyncStatus.Synchronizing(SyncReason.RECONNECTED), pulled.state.model.sync)
        assertEquals(7L, pulled.state.model.mirror.objects.getValue("lamp").revision)
        assertEquals(7L, pulled.state.lastRevision)
        assertEquals(setOf("lamp"), pulled.update?.changed)
        assertEquals(
            SyncStatus.Synchronizing(SyncReason.RECONNECTED),
            reducer.apply(down, ObservationInput.SnapshotFailed("no answer")).state.model.sync,
            "a failed read while the link is down still waits for the reconnect",
        )
        assertUnchanged(pulled.state, ObservationInput.Pushed(RuntimePush.StateChanged("lamp", listOf("on"), 8)))

        val reopened = reducer.apply(pulled.state, ObservationInput.LinkOpened(2))
        assertEquals(listOf(ObservationRequest.Resubscribe, ObservationRequest.ReadSnapshot), reopened.requests)
        val live = reducer.apply(reopened.state, ObservationInput.SnapshotRead(snapshot(revision = 9))).state
        assertEquals(SyncStatus.Live(9), live.model.sync)
    }

    @Test
    fun `a link that went down while off keeps a model switched on meanwhile from going live until it opens again`() {
        val off = reducer.apply(live(at = 5), ObservationInput.SwitchedOff).state
        val down = reducer.apply(off, ObservationInput.LinkInterrupted).state
        val on = reducer.apply(down, ObservationInput.SwitchedOn).state

        val read = reducer.apply(on, ObservationInput.SnapshotRead(snapshot(revision = 6))).state

        assertEquals(SyncStatus.Synchronizing(SyncReason.RECONNECTED), read.model.sync)
        val reopened = reducer.apply(read, ObservationInput.LinkOpened(2)).state
        assertEquals(SyncStatus.Live(6), reducer.apply(reopened, ObservationInput.SnapshotRead(snapshot(revision = 6))).state.model.sync)
    }

    @Test
    fun `the stop switches observation off and asks for the final snapshot, and switching on afterwards changes nothing`() {
        val stopped = reducer.apply(live(at = 5), ObservationInput.EnvironmentStopped)

        assertEquals(ObservationMode.OFF, stopped.state.model.mode)
        assertEquals(SyncStatus.Pulled(5), stopped.state.model.sync)
        assertEquals(listOf(ObservationRequest.ReadSnapshot), stopped.requests)
        assertTrue(stopped.state.stopped)
        val again = reducer.apply(stopped.state, ObservationInput.SwitchedOn)
        assertSame(stopped.state, again.state)
        assertEquals(1, again.anomalies.size)
        assertUnchanged(stopped.state, ObservationInput.SwitchedOff)
    }

    @Test
    fun `domain events and unrecognized pushes are not the observation's business`() {
        val live = live(at = 5)
        val event = PublishedEvent("press", "", "none", "EXTERNAL_MESSAGE", 1_761_560_130_123, 3_000)

        assertUnchanged(live, ObservationInput.Pushed(RuntimePush.DomainEvent("switch", event, "{}")))
        assertUnchanged(live, ObservationInput.Pushed(RuntimePush.Unrecognized("somethingNew", "{}")))
    }

    /** The state after switching on and reading a snapshot of the seed at revision [at] with [activeStates]. */
    private fun live(at: Long, activeStates: Map<String, List<String>> = emptyMap()): ObservationState {
        val synchronizing = reducer.apply(reducer.initial(lamp.seed), ObservationInput.SwitchedOn).state
        return reducer.apply(synchronizing, ObservationInput.SnapshotRead(snapshot(revision = at, activeStates = activeStates))).state
    }

    /** [state] after the [push], which must be applied. */
    private fun applied(state: ObservationState, push: RuntimePush): ObservationState =
        reducer.apply(state, ObservationInput.Pushed(push)).also { checkNotNull(it.update) { "the push was not applied" } }.state

    /** Checks that [input] leaves [state] as it is, without update, requests or anomalies. */
    private fun assertUnchanged(state: ObservationState, input: ObservationInput) {
        val step = reducer.apply(state, input)
        assertSame(state, step.state, "the state after $input")
        assertNull(step.update, "the update after $input")
        assertEquals(emptyList(), step.requests, "the requests after $input")
        assertEquals(emptyList(), step.anomalies, "the anomalies after $input")
    }

    /** A push of the lamp's bulb with [watts] at [revision]. */
    private fun bulb(revision: Long, watts: Int): RuntimePush.ObjectChanged =
        WireTexts.changed("bulb", revision, "{\"__id__\": \"bulb\", \"__type__\": \"Bulb\", \"watts\": $watts, \"lit\": false}")

    /** The roots of the lamp model with the lamp in [mode]. */
    private fun rootsWith(mode: String): List<WireValue.WireObject> =
        listOf(WireTexts.wireObject(lamp.wire.rootsJson().single().replace("\"mode\": \"off\"", "\"mode\": \"$mode\"")))

    /** A snapshot of the lamp's environment. */
    private fun snapshot(
        revision: Long,
        phase: RuntimePhase = RuntimePhase.RUNNING,
        roots: List<WireValue.WireObject> = lamp.wireRoots(),
        activeStates: Map<String, List<String>> = emptyMap(),
    ): RuntimeModelSnapshot = RuntimeModelSnapshot(EnvironmentKey("env-1"), phase, revision, activeStates, roots)

    private companion object {
        /** The lamp's machine. */
        val LAMP = MachineLayout("lamp", Path.of("lamp.kstates"), listOf("off", "on", "broken"), listOf("off"))

        /** The wall switch's machine. */
        val SWITCH = MachineLayout("wallSwitch", Path.of("switch.kstates"), listOf("down", "up", "back down"), listOf("down"))

        /** The spare bulb as the runtime writes it. */
        const val SPARE = "{\"__id__\": \"spare\", \"__type__\": \"Bulb\", \"watts\": 60, \"lit\": false}"

        /** A spare bulb the runtime created in place of the model's. */
        const val GHOST_SPARE = "{\"__id__\": \"gen_Bulb_1\", \"__type__\": \"Bulb\", \"watts\": 60, \"lit\": false}"
    }
}
