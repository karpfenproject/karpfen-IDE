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
package io.karpfen.ide.runtime.session.states

import io.karpfen.dsl.states.State
import io.karpfen.dsl.states.actions.ActionBlock
import io.karpfen.ide.runtime.session.examples.AnalyzedContexts
import io.karpfen.ide.runtime.session.examples.DslTexts
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the active state configurations. It checks the layouts and predicted initial stacks of the examples and
 * synthesized models, the split of a flat stack into its parallel branches, and the reducer's predictions, reports and
 * snapshots.
 */
class ActiveStatesTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `the initial stacks of the examples and synthesized models follow the runtime's rule`() = runTest {
        val contexts = AnalyzedContexts.runtimeExamples(this, root.resolve("examples")) +
            AnalyzedContexts.allSynthesized(this, root.resolve("synthesized"))

        val stacks = contexts.flatMap { context -> context.layouts.map { "${context.name}/${it.attachedTo}" to it.initialStack } }

        assertEquals(
            listOf(
                "single_robot_example/turtle" to listOf("ready"),
                "delivery_drone_example/scout" to listOf("idle"),
                "autonomous_car_example/av-1" to listOf("off"),
                "drone_swarm_example/alpha-swarm" to listOf("standby"),
                "lamp/lamp" to listOf("off"),
                "lamp/wallSwitch" to listOf("down"),
                "metronome/metronome" to listOf("rest"),
                "depot/depot" to listOf("idle"),
                "ring/a" to listOf("visit"),
                "crossing/crossing" to listOf("operating", "cars go"),
            ),
            stacks,
        )
        assertEquals(
            listOf("ready", "observe", "drive"),
            contexts.first().layouts.single().topLevelStates,
        )
    }

    @Test
    fun `the autonomous car's seven stacks split into one branch per top-level state`() = runTest {
        val car = AnalyzedContexts.runtimeExamples(this, root).single { it.name == "autonomous_car_example" }
        val reducer = ActiveStatesReducer(car.layouts)
        val key = car.layouts.single().attachedTo

        val regions = CAR_STACKS.map { stack -> reducer.reported(reducer.initial(), key, stack).machines.getValue(key).regions }

        assertEquals(
            listOf(
                listOf(listOf("off")),
                listOf(listOf("checkBattery"), listOf("checkBrakes"), listOf("calibrateSensors")),
                listOf(listOf("batteryReady"), listOf("bleedBrakes"), listOf("sensorsReady")),
                listOf(listOf("batteryReady"), listOf("brakesReady"), listOf("sensorsReady")),
                listOf(listOf("ready")),
                listOf(listOf("driving")),
                listOf(listOf("parked")),
            ),
            regions,
        )
    }

    @Test
    fun `a branch path keeps its nested states, and a nested name equal to a top-level name starts a branch too`() {
        val segmenter = StackSegmenter(listOf("observe", "drive", "idle"))

        assertEquals(listOf(listOf("observe", "react to obstacle")), segmenter.regions(listOf("observe", "react to obstacle")))
        assertEquals(listOf(listOf("drive", "fast"), listOf("observe")), segmenter.regions(listOf("drive", "fast", "observe")))
        assertEquals(listOf(listOf("drive", "fast"), listOf("idle", "x")), segmenter.regions(listOf("drive", "fast", "idle", "x")))
        assertEquals(listOf(listOf("orphan"), listOf("drive")), segmenter.regions(listOf("orphan", "drive")))
        assertEquals(emptyList(), segmenter.regions(emptyList()))
    }

    @Test
    fun `the initial stack takes the state marked initial at every level, or else the first, down the inner states`() {
        val marked = DslTexts.machine(
            """
            STATEMACHINE ATTACHED TO "Robot" {
                STATES {
                    STATE "a" { }
                    INITIAL STATE "b" {
                        STATE "b1" {
                            STATE "b1x" { }
                        }
                        STATE "b2" { }
                    }
                }
                TRANSITIONS { }
            }
            """.trimIndent(),
        )
        val layout = MachineLayout.of("robot", Path.of("robot.kstates"), marked)

        assertEquals(listOf("b", "b1", "b1x"), layout.initialStack)
        assertEquals(listOf("a", "b"), layout.topLevelStates)
        assertEquals(listOf("first", "inner"), InitialStackPredictor.predict(listOf(state("first", state("inner")), state("second"))))
        assertEquals(emptyList(), InitialStackPredictor.predict(emptyList()))
    }

    @Test
    fun `a machine shows its prediction until a report replaces it, keeping the stack before for animation`() {
        val reducer = ActiveStatesReducer(listOf(LAMP, SWITCH))

        val initial = reducer.initial()
        val on = reducer.reported(initial, "lamp", listOf("on"))
        val full = reducer.reported(on, "lamp", listOf("on", "full power"))

        assertEquals(listOf("lamp", "wallSwitch"), initial.machines.keys.toList())
        assertEquals(
            MachineActivity("lamp", LAMP.file, listOf("off"), listOf(listOf("off")), null, StackOrigin.PREDICTED),
            initial.machines.getValue("lamp"),
        )
        assertEquals(
            MachineActivity("lamp", LAMP.file, listOf("on"), listOf(listOf("on")), listOf("off"), StackOrigin.REPORTED),
            on.machines.getValue("lamp"),
        )
        assertEquals(listOf(listOf("on", "full power")), full.machines.getValue("lamp").regions)
        assertEquals(listOf("on"), full.machines.getValue("lamp").previous)
        assertEquals(initial.machines.getValue("wallSwitch"), full.machines.getValue("wallSwitch"))
        assertEquals(emptyMap(), full.unknownElements)
    }

    @Test
    fun `reporting the stack a machine already shows keeps its previous stack`() {
        val reducer = ActiveStatesReducer(listOf(LAMP))

        val confirmed = reducer.reported(reducer.initial(), "lamp", listOf("off"))
        val on = reducer.reported(confirmed, "lamp", listOf("on"))
        val again = reducer.reported(on, "lamp", listOf("on"))

        assertNull(confirmed.machines.getValue("lamp").previous)
        assertEquals(StackOrigin.REPORTED, confirmed.machines.getValue("lamp").origin)
        assertEquals(listOf("off"), again.machines.getValue("lamp").previous)
    }

    @Test
    fun `a snapshot reports the machines it lists, returns the others to their prediction and replaces the unknown elements`() {
        val reducer = ActiveStatesReducer(listOf(LAMP, SWITCH))
        val before = reducer.reported(reducer.reported(reducer.initial(), "lamp", listOf("on")), "ghost", listOf("haunting"))

        val replaced = reducer.replaced(before, mapOf("wallSwitch" to listOf("up"), "phantom" to listOf("x")))

        assertEquals(
            MachineActivity("lamp", LAMP.file, listOf("off"), listOf(listOf("off")), listOf("on"), StackOrigin.PREDICTED),
            replaced.machines.getValue("lamp"),
        )
        assertEquals(
            MachineActivity("wallSwitch", SWITCH.file, listOf("up"), listOf(listOf("up")), listOf("down"), StackOrigin.REPORTED),
            replaced.machines.getValue("wallSwitch"),
        )
        assertEquals(mapOf("phantom" to listOf("x")), replaced.unknownElements)
        assertEquals(mapOf("ghost" to listOf("haunting")), before.unknownElements)
    }

    @Test
    fun `a report for a key the deployment attaches no machine to goes to the unknown elements`() {
        val reducer = ActiveStatesReducer(listOf(LAMP))

        val states = reducer.reported(reducer.reported(reducer.initial(), "ghost", listOf("a")), "ghost", listOf("b"))

        assertEquals(mapOf("ghost" to listOf("b")), states.unknownElements)
        assertEquals(setOf("lamp"), states.machines.keys)
    }

    @Test
    fun `a key attached twice keeps its first layout`() {
        val reducer = ActiveStatesReducer(listOf(LAMP, LAMP.copy(file = Path.of("other.kstates"), initialStack = listOf("other"))))

        assertEquals(listOf("off"), reducer.initial().machines.getValue("lamp").stack)
    }

    private companion object {
        /** The stacks the runtime reports for the autonomous car from ignition to parking. */
        val CAR_STACKS = listOf(
            listOf("off"),
            listOf("checkBattery", "checkBrakes", "calibrateSensors"),
            listOf("batteryReady", "bleedBrakes", "sensorsReady"),
            listOf("batteryReady", "brakesReady", "sensorsReady"),
            listOf("ready"),
            listOf("driving"),
            listOf("parked"),
        )

        /** The lamp's machine of the synthesized lamp model. */
        val LAMP = MachineLayout("lamp", Path.of("lamp.kstates"), listOf("off", "on", "broken"), listOf("off"))

        /** The wall switch's machine of the synthesized lamp model. */
        val SWITCH = MachineLayout("wallSwitch", Path.of("switch.kstates"), listOf("down", "up", "back down"), listOf("down"))

        /** A state [name] without actions and not marked initial, with [inner] states. */
        fun state(name: String, vararg inner: State): State =
            State(name, ActionBlock(mutableListOf()), ActionBlock(mutableListOf()), inner.toMutableList())
    }
}
