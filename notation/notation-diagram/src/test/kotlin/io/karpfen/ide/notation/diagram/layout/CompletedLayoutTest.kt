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
package io.karpfen.ide.notation.diagram.layout

import io.karpfen.ide.notation.diagram.LampStateMachine
import io.karpfen.ide.notation.diagram.LampStructure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Tests how a layout by hand is completed with the places of the boxes it lacks: every box that is drawn gets a stored
 * place, the picture stays the same, and a state with nested states keeps following the states inside it. The layouts
 * hold a frame, a waypoint and a moved label, so the comparison of the pictures covers more than the boxes.
 */
class CompletedLayoutTest {

    private val text = TextMeasurer.estimating()
    private val lamp = LampStateMachine.diagramOf(LampStateMachine.LAMP)
    private val toBroken = lamp.transitions.first { it.sources == listOf("off") && it.targets == listOf("broken") }.id
    private val toFull = lamp.transitions.first { it.targets == listOf("full") }.id

    /** A layout by hand of the lamp in which "on" was sized by the user, with a bent line and a moved caption. */
    private val stored = VisLayout(
        boxes = mapOf(
            "off" to DiagramPoint(100f, 100f),
            "full" to DiagramPoint(300f, 200f),
            "brighter" to DiagramPoint(500f, 200f),
            "broken" to DiagramPoint(100f, 400f),
        ),
        waypoints = mapOf(toBroken to listOf(DiagramPoint(40f, 250f))),
        labels = mapOf(toFull to DiagramPoint(20f, -10f)),
        frames = mapOf("on" to DiagramBox(200f, 120f, 460f, 200f)),
    )

    @Test
    fun `a new state at the top level gets the place where it is drawn, and the picture stays the same`() {
        val vis = stored.copy(boxes = stored.boxes - "broken")

        val completed = completedStatechart(vis)

        assertEquals(setOf("off", "full", "brighter", "broken"), completed.boxes.keys)
        assertEquals(vis.copy(boxes = completed.boxes), completed, "nothing but the new place is added")
        assertEquals(vis.boxes, completed.boxes - "broken", "the stored places stay")
        assertEquals(StatechartArrangement.byHand(lamp, vis, text), StatechartArrangement.byHand(lamp, completed, text))
    }

    @Test
    fun `a new state inside a stored state with nested states gets its place, and its parent keeps its frame and no place`() {
        val vis = stored.copy(boxes = stored.boxes - "brighter")

        val completed = completedStatechart(vis)

        assertEquals(setOf("off", "full", "brighter", "broken"), completed.boxes.keys)
        assertEquals(vis.frames, completed.frames, "the frame of the parent is in effect and stays")
        assertEquals(StatechartArrangement.byHand(lamp, vis, text), StatechartArrangement.byHand(lamp, completed, text))
    }

    @Test
    fun `a new state whose nested states are all new gives each of them a place and none to itself`() {
        val vis = stored.copy(boxes = stored.boxes - "full" - "brighter", frames = emptyMap())

        val completed = completedStatechart(vis)

        assertEquals(setOf("off", "full", "brighter", "broken"), completed.boxes.keys)
        assertEquals(emptyMap(), completed.frames)
        assertEquals(StatechartArrangement.byHand(lamp, vis, text), StatechartArrangement.byHand(lamp, completed, text))
    }

    @Test
    fun `a frame left from before the nested states were all replaced does not come into effect by completing`() {
        val vis = stored.copy(boxes = stored.boxes - "full" - "brighter")
        val drawn = StatechartArrangement.byHand(lamp, vis, text)

        val completed = completedStatechart(vis)

        assertEquals(setOf("off", "full", "brighter", "broken"), completed.boxes.keys)
        assertEquals(drawn, StatechartArrangement.byHand(lamp, completed, text))
        assertEquals(emptyMap(), completed.frames, "the frame was not in effect, so it is dropped")
    }

    @Test
    fun `once completed, a new state with nested states moves together with them`() {
        val vis = stored.copy(boxes = stored.boxes - "full" - "brighter")
        val before = StatechartArrangement.byHand(lamp, vis, text)

        val moved = StatechartArrangement.moveBox(lamp, completedStatechart(vis), "on", DiagramPoint(40f, 60f))

        val after = StatechartArrangement.byHand(lamp, moved, text)
        listOf("on", "full", "brighter").forEach { name ->
            val was = before.states.getValue(name)
            assertEquals(was.copy(x = was.x + 40f, y = was.y + 60f), after.states.getValue(name), name)
        }
        assertEquals(before.states.getValue("broken"), after.states.getValue("broken"), "a new state beside it stays")
    }

    @Test
    fun `a layout that has every box is given back as it is`() {
        assertSame(stored, completedStatechart(stored))
    }

    @Test
    fun `a new box of a class diagram gets the place where it is drawn, and the picture stays the same`() {
        val types = LampStructure.typesOf(LampStructure.METAMODEL)
        val bent = types.links.first { it.source == "Lamp" && it.target == "Bulb" }.id
        val labelled = types.links.first { it.source == "Room" && it.label.isNotEmpty() }.id
        val vis = VisLayout(
            boxes = mapOf("Bulb" to DiagramPoint(100f, 300f), "Lamp" to DiagramPoint(100f, 100f)),
            waypoints = mapOf(bent to listOf(DiagramPoint(200f, 200f))),
            labels = mapOf(labelled to DiagramPoint(10f, 20f)),
        )
        val drawn = StructureArrangement.byHand(types, vis, text)

        val completed = StructureArrangement.completed(types, vis, drawn)

        assertEquals(setOf("Bulb", "Switch", "Lamp", "Room"), completed.boxes.keys)
        assertEquals(vis.copy(boxes = completed.boxes), completed, "nothing but the new places is added")
        assertEquals(vis.boxes, completed.boxes.filterKeys(vis.boxes::containsKey))
        assertEquals(drawn, StructureArrangement.byHand(types, completed, text))
    }

    private fun completedStatechart(vis: VisLayout): VisLayout =
        StatechartArrangement.completed(lamp, vis, StatechartArrangement.byHand(lamp, vis, text))
}
