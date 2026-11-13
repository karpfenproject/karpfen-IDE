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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tests the layout by hand: stored places are kept, the rest follows from them, and only new elements are placed. */
class ManualStatechartLayoutTest {

    private val text = TextMeasurer.estimating()
    private val lamp = LampStateMachine.diagramOf(LampStateMachine.LAMP)
    private val vis = VisLayout(
        boxes = mapOf(
            "off" to DiagramPoint(100f, 100f),
            "full" to DiagramPoint(300f, 200f),
            "brighter" to DiagramPoint(500f, 200f),
            "broken" to DiagramPoint(100f, 400f),
        ),
    )
    private val layout = ManualStatechartLayout.of(lamp, vis, text)

    @Test
    fun `a state without nested states sits with its middle on its stored point`() {
        vis.boxes.forEach { (name, point) -> assertEquals(point, layout.states.getValue(name).center, name) }
    }

    @Test
    fun `a state with nested states surrounds them and leaves room for its name`() {
        val on = layout.states.getValue("on")
        val inner = layout.states.getValue("full").union(layout.states.getValue("brighter"))

        assertEquals(inner.x - StatechartMetrics.INNER_MARGIN, on.x)
        assertEquals(inner.right + StatechartMetrics.INNER_MARGIN, on.right)
        assertEquals(inner.y - StatechartMetrics.INNER_MARGIN - StatechartMetrics.HEADER, on.y)
        assertEquals(inner.bottom + StatechartMetrics.INNER_MARGIN, on.bottom)
    }

    @Test
    fun `a transition is a straight line from border to border, with its caption beside its middle`() {
        val line = layout.routes.single { it.edgeId == "NORMAL:off->broken#1" }
        val off = layout.states.getValue("off")
        val broken = layout.states.getValue("broken")

        assertEquals(listOf(DiagramPoint(100f, off.bottom), DiagramPoint(100f, broken.y)), line.points)
        val label = assertNotNull(line.label)
        val middle = Geometry.middle(line.points)
        assertEquals(middle.y, label.center.y, "the caption is at the height of the middle of the upright line")
        assertTrue(label.x > middle.x, "the caption stands to the right of the line, not on it")
    }

    @Test
    fun `a transition runs through its waypoints`() {
        val bent = vis.copy(waypoints = mapOf("NORMAL:off->broken#1" to listOf(DiagramPoint(40f, 200f), DiagramPoint(40f, 300f))))

        val line = ManualStatechartLayout.of(lamp, bent, text).routes.single { it.edgeId == "NORMAL:off->broken#1" }

        assertEquals(listOf(DiagramPoint(40f, 200f), DiagramPoint(40f, 300f)), line.points.subList(1, 3))
        assertEquals(4, line.points.size)
    }

    @Test
    fun `a transition from a parent into its nested state runs straight down from the top of the parent`() {
        val line = layout.routes.single { it.edgeId == "NORMAL:on->brighter#1" }
        val on = layout.states.getValue("on")
        val brighter = layout.states.getValue("brighter")

        assertEquals(listOf(DiagramPoint(brighter.center.x, on.y), DiagramPoint(brighter.center.x, brighter.y)), line.points)
    }

    @Test
    fun `a line to a waypoint that is lined up with a state meets the state at a right angle`() {
        val off = layout.states.getValue("off")
        val broken = layout.states.getValue("broken")
        val beside = DiagramPoint(off.right + 60f, off.center.y + 5f)
        val bent = vis.copy(waypoints = mapOf("NORMAL:off->broken#1" to listOf(beside, DiagramPoint(beside.x, broken.center.y))))

        val line = ManualStatechartLayout.of(lamp, bent, text).routes.single { it.edgeId == "NORMAL:off->broken#1" }

        assertEquals(DiagramPoint(off.right, beside.y), line.points.first(), "the line leaves off sideways at the height of the waypoint")
        assertEquals(DiagramPoint(broken.right, broken.center.y), line.points.last())
    }

    @Test
    fun `the marker of the initial state sits above it`() {
        val marker = layout.initialMarkers.getValue("off")
        val off = layout.states.getValue("off")

        assertEquals(off.center.x, marker.center.x)
        assertEquals(off.y - StatechartMetrics.MARKER_GAP, marker.bottom)
    }

    @Test
    fun `a state that is new in the text is placed to the right of its siblings and nothing else moves`() {
        val withoutBroken = vis.copy(boxes = vis.boxes - "broken")

        val result = ManualStatechartLayout.of(lamp, withoutBroken, text)

        (vis.boxes - "broken").forEach { (name, point) -> assertEquals(point, result.states.getValue(name).center, name) }
        val broken = result.states.getValue("broken")
        val siblings = result.states.getValue("off").union(result.states.getValue("on"))
        assertTrue(broken.x >= siblings.right + StatechartMetrics.GAP - DiagramGrid.STEP, "broken is not right of its siblings: $broken")
        assertEquals(DiagramGrid.snap(broken.center), broken.center, "the new state sits on the grid")
    }

    @Test
    fun `a new nested state is placed inside its parent, next to the others`() {
        val withoutBrighter = vis.copy(boxes = vis.boxes - "brighter")

        val result = ManualStatechartLayout.of(lamp, withoutBrighter, text)

        val on = result.states.getValue("on")
        val brighter = result.states.getValue("brighter")
        assertTrue(brighter.x >= result.states.getValue("full").right, "brighter is not right of full")
        assertTrue(brighter.x >= on.x && brighter.right <= on.right && brighter.bottom <= on.bottom, "brighter sticks out of on")
    }

    @Test
    fun `without any stored place every state is placed in a row`() {
        val result = ManualStatechartLayout.of(lamp, VisLayout(), text)

        assertEquals(setOf("off", "on", "full", "brighter", "broken"), result.states.keys)
        val top = listOf("off", "on", "broken").map { result.states.getValue(it) }
        top.zipWithNext().forEach { (left, right) -> assertTrue(left.right < right.x, "$left overlaps $right") }
    }

    @Test
    fun `a split has its bar on the stored point or between its states`() {
        val fork = LampStateMachine.diagramOf(LampStateMachine.FORK)
        val places = VisLayout(
            boxes = mapOf(
                "idle" to DiagramPoint(200f, 100f),
                "left" to DiagramPoint(100f, 300f),
                "right" to DiagramPoint(300f, 300f),
                "done" to DiagramPoint(200f, 500f),
            ),
        )
        val split = fork.transitions.first().id

        val between = ManualStatechartLayout.of(fork, places, text)
        val moved = ManualStatechartLayout.of(fork, places.copy(bars = mapOf(split to DiagramPoint(400f, 200f))), text)

        assertEquals(DiagramGrid.snap(DiagramPoint(200f, 700f / 3f)), between.bars.getValue(split).center)
        assertEquals(DiagramPoint(400f, 200f), moved.bars.getValue(split).center)
        assertEquals(3, between.routes.count { it.edgeId == split })
    }

    private val crossing = LampStateMachine.diagramOf(LampStateMachine.CROSSING)
    private val crossingVis = VisLayout(
        boxes = mapOf(
            "cars go" to DiagramPoint(100f, 100f),
            "cars wait" to DiagramPoint(100f, 300f),
            "green" to DiagramPoint(400f, 300f),
            "blinking" to DiagramPoint(400f, 500f),
        ),
    )

    @Test
    fun `a timeout runs beside a transition between the same states and through its own waypoints with its own caption`() {
        val layout = ManualStatechartLayout.of(crossing, crossingVis, text)
        val transition = layout.routes.single { it.edgeId == "NORMAL:cars go->cars wait#1" }
        val timeout = layout.routes.single { it.edgeId == "TIMEOUT:cars go" }

        assertEquals(RouteKind.TIMEOUT, timeout.kind)
        assertEquals(listOf(100f, 100f), transition.points.map { it.x }, "the transition stays in the middle")
        assertEquals(listOf(100f - StatechartMetrics.SPREAD, 100f - StatechartMetrics.SPREAD), timeout.points.map { it.x }, "left of the transition")
        val caption = assertNotNull(timeout.label)
        assertEquals(StatechartMetrics.captionWidth(assertNotNull(crossing.edge("TIMEOUT:cars go")), text), caption.width)
        assertTrue(caption.right < timeout.points.first().x && assertNotNull(transition.label).x > 100f, "the captions stand on the far sides")

        val bent = crossingVis.copy(
            waypoints = mapOf("TIMEOUT:cars go" to listOf(DiagramPoint(250f, 200f))),
            labels = mapOf("TIMEOUT:cars go" to DiagramPoint(0f, 30f)),
        )
        val moved = ManualStatechartLayout.of(crossing, bent, text).routes.single { it.edgeId == "TIMEOUT:cars go" }
        assertEquals(DiagramPoint(250f, 200f), moved.points[1])
        assertEquals(3, moved.points.size)
        val usual = LineRouting.captionBeside(moved.points, assertNotNull(moved.label).width, StatechartMetrics.LABEL_HEIGHT)
        assertEquals(usual.y + 30f, assertNotNull(moved.label).y)
    }

    @Test
    fun `a timeout between nested states is drawn, and one to a state the text does not have is not`() {
        val layout = ManualStatechartLayout.of(crossing, crossingVis, text)

        val inner = layout.routes.single { it.edgeId == "TIMEOUT:green" }
        assertEquals(listOf(DiagramPoint(400f, layout.states.getValue("green").bottom), DiagramPoint(400f, layout.states.getValue("blinking").y)), inner.points)
        assertTrue(layout.routes.none { it.edgeId == "TIMEOUT:blinking" })
        assertEquals(setOf("TIMEOUT:cars go", "TIMEOUT:cars wait", "TIMEOUT:green"), layout.labels.keys.filter { it.startsWith("TIMEOUT") }.toSet())
    }

    @Test
    fun `each line of a split or join runs through its own waypoints, and a layout without them keeps its bar`() {
        val fork = LampStateMachine.diagramOf(LampStateMachine.FORK)
        val split = fork.transitions.first()
        val bar = DiagramPoint(200f, 200f)
        val old = VisLayout(
            boxes = mapOf(
                "idle" to DiagramPoint(200f, 100f),
                "left" to DiagramPoint(100f, 300f),
                "right" to DiagramPoint(300f, 300f),
                "done" to DiagramPoint(200f, 500f),
            ),
            bars = mapOf(split.id to bar),
            labels = mapOf(split.id to DiagramPoint(10f, 0f)),
        )

        val straight = ManualStatechartLayout.of(fork, old, text)
        assertEquals(bar, straight.bars.getValue(split.id).center, "a layout written before the lines could be bent keeps its bar")
        straight.routes.filter { it.edgeId == split.id }.forEach { assertEquals(2, it.points.size, "${it.lineId} is straight") }
        assertEquals(split.lineIds, straight.routes.filter { it.edgeId == split.id }.map { it.lineId })

        val left = split.lineTo("left")
        val bent = ManualStatechartLayout.of(fork, old.copy(waypoints = mapOf(left to listOf(DiagramPoint(100f, 200f)))), text)
        val line = bent.routes.single { it.lineId == left }
        val barBox = bent.bars.getValue(split.id)
        assertEquals(listOf(DiagramPoint(barBox.x, 200f), DiagramPoint(100f, 200f), DiagramPoint(100f, bent.states.getValue("left").y)), line.points)
        assertEquals(straight.routes.single { it.lineId == split.lineTo("right") }, bent.routes.single { it.lineId == split.lineTo("right") }, "the other line stays")
        assertEquals(left, bent.lineAt(DiagramPoint(130f, 201f), StatechartLayout.HIT_TOLERANCE)?.id)
    }
}
