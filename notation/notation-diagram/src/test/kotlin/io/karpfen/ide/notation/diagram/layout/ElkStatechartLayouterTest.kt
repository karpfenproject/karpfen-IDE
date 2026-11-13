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

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.notation.diagram.LampStateMachine
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tests the automatic layout of statecharts: every element gets a place, and the places make sense. */
class ElkStatechartLayouterTest {

    private val lamp = LampStateMachine.diagramOf(LampStateMachine.LAMP)
    private val layout = StatechartLayouter().layout(lamp, TextMeasurer.estimating())

    @Test
    fun `every state gets a box inside the drawing`() {
        assertEquals(setOf("off", "on", "full", "brighter", "broken"), layout.states.keys)
        layout.states.forEach { (name, box) ->
            assertTrue(box.width > 0f && box.height > 0f, "$name has no size: $box")
            assertTrue(box.x >= 0f && box.y >= 0f && box.right <= layout.width && box.bottom <= layout.height, "$name is outside: $box")
        }
    }

    @Test
    fun `nested states lie inside their parent, below its name`() {
        val on = layout.states.getValue("on")
        listOf("full", "brighter").forEach { name ->
            val child = layout.states.getValue(name)
            assertTrue(child.x >= on.x && child.right <= on.right, "$name sticks out of on sideways")
            assertTrue(child.y >= on.y + layout.headerHeight && child.bottom <= on.bottom, "$name sticks out of on or covers its name")
        }
    }

    @Test
    fun `states of one level do not overlap`() {
        val top = listOf("off", "on", "broken").map { layout.states.getValue(it) }
        for (i in top.indices) {
            for (j in i + 1 until top.size) {
                val a = top[i]
                val b = top[j]
                assertTrue(a.right <= b.x || b.right <= a.x || a.bottom <= b.y || b.bottom <= a.y, "$a overlaps $b")
            }
        }
    }

    @Test
    fun `every transition gets a line with a label, and the initial state a marker`() {
        val lines = layout.routes.filter { it.kind == RouteKind.TRANSITION }
        assertEquals(lamp.transitions.map { it.id }.toSet(), lines.map { it.edgeId }.toSet())
        lines.forEach { line ->
            assertTrue(line.points.size >= 2, "the line of ${line.edgeId} has no path")
            assertNotNull(line.label, "the line of ${line.edgeId} has no label")
        }
        assertEquals(setOf("off"), layout.initialMarkers.keys)
        assertEquals(1, layout.routes.count { it.kind == RouteKind.INITIAL })
    }

    @Test
    fun `the drawing starts at the initial state`() {
        val marker = layout.initialMarkers.getValue("off")
        val off = layout.states.getValue("off")

        assertTrue(marker.bottom <= off.y, "the marker is not above the initial state")
        assertTrue(layout.states.filterKeys { it != "off" }.values.all { it.y >= off.y }, "a state lies above the initial one")
    }

    @Test
    fun `a point finds the innermost state around it`() {
        val on = layout.states.getValue("on")
        val full = layout.states.getValue("full")

        assertEquals("full", layout.stateAt(DiagramPoint(full.x + 1f, full.y + 1f)))
        assertEquals("on", layout.stateAt(DiagramPoint(on.x + 1f, on.y + 1f)), "the name row of on belongs to on")
        assertEquals(null, layout.stateAt(DiagramPoint(-5f, -5f)))
    }

    @Test
    fun `a split and a join each get a bar with a line per state`() {
        val fork = LampStateMachine.diagramOf(LampStateMachine.FORK)

        val forkLayout = StatechartLayouter().layout(fork, TextMeasurer.estimating())

        assertEquals(fork.transitions.map { it.id }.toSet(), forkLayout.bars.keys)
        assertEquals(3, forkLayout.routes.count { it.kind == RouteKind.INTO_BAR })
        assertEquals(3, forkLayout.routes.count { it.kind == RouteKind.OUT_OF_BAR })
        assertEquals(2, forkLayout.routes.count { it.label != null }, "each of the two carries its label once")
    }

    @Test
    fun `each line of a split or join carries its own id, and the bar and the caption the id of the transition`() {
        val fork = LampStateMachine.diagramOf(LampStateMachine.FORK)

        val forkLayout = StatechartLayouter().layout(fork, TextMeasurer.estimating())

        val forked = forkLayout.routes.filter { it.kind == RouteKind.INTO_BAR || it.kind == RouteKind.OUT_OF_BAR }
        assertEquals(fork.transitions.flatMap { it.lineIds }.toSet(), forked.mapNotNull { it.lineId }.toSet())
        assertEquals(fork.transitions.map { it.id }.toSet(), forkLayout.labels.keys)
        forked.forEach { route ->
            val transition = fork.transitions.single { it.id == route.edgeId }
            assertTrue(route.lineId in transition.lineIds, "${route.lineId} is no line of ${transition.id}")
        }
    }

    @Test
    fun `a timeout is a line of its own with its time as caption, and one to an unknown state is left out`() {
        val crossing = LampStateMachine.diagramOf(LampStateMachine.CROSSING)

        val result = StatechartLayouter().layout(crossing, TextMeasurer.estimating())

        val timeouts = result.routes.filter { it.kind == RouteKind.TIMEOUT }
        assertEquals(listOf("TIMEOUT:cars go", "TIMEOUT:cars wait", "TIMEOUT:green"), timeouts.map { it.edgeId })
        timeouts.forEach { route ->
            assertEquals(route.edgeId, route.lineId)
            assertTrue(route.points.size >= 2, "the line of ${route.edgeId} has no path")
            assertNotNull(route.label, "the line of ${route.edgeId} has no caption")
        }
        val caption = assertNotNull(result.labels["TIMEOUT:cars go"])
        assertEquals(TextMeasurer.estimating().widthOf("after 10 ticks") + StatechartMetrics.LABEL_PADDING, caption.width, "no room for a cell")
        assertEquals(crossing.transitions.size, result.routes.count { it.kind == RouteKind.TRANSITION })
    }

    @Test
    fun `a diagram without states has an empty layout`() {
        assertEquals(StatechartLayout.EMPTY, StatechartLayouter().layout(StatechartDiagram(null, emptyList(), emptyList(), TextRange(0, 0)), TextMeasurer.estimating()))
    }

    @Test
    fun `a transition to a state that does not exist is left out`() {
        val broken = lamp.copy(transitions = lamp.transitions + lamp.transitions.first().copy(id = "x", targets = listOf("nowhere")))

        val result = StatechartLayouter().layout(broken, TextMeasurer.estimating())

        assertTrue(result.routes.none { it.edgeId == "x" })
        assertEquals(layout.states.keys, result.states.keys)
    }
}
