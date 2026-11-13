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
import io.karpfen.ide.notation.diagram.edit.StatechartEdits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the layout by hand of the lines that are not normal transitions: the timeouts of states and the lines of
 * splits and joins between a state and the bar. Each is stored under an id of its own and takes part in freezing,
 * pruning, moving, renaming and the file like the line of a normal transition.
 */
class StatechartLinesTest {

    private val text = TextMeasurer.estimating()
    private val fork = LampStateMachine.diagramOf(LampStateMachine.FORK)
    private val split = fork.transitions[0]
    private val join = fork.transitions[1]
    private val crossing = LampStateMachine.diagramOf(LampStateMachine.CROSSING)

    @Test
    fun `the lines that can be bent and the captions that can be moved include timeouts and the lines of splits and joins`() {
        assertEquals(split.lineIds.toSet() + join.lineIds, StatechartArrangement.lines(fork))
        assertEquals(setOf(split.id, join.id), StatechartArrangement.labelled(fork))
        assertTrue(crossing.timeouts().map { it.id }.all { it in StatechartArrangement.lines(crossing) && it in StatechartArrangement.labelled(crossing) })
    }

    @Test
    fun `freezing keeps the corners of every line of a split, a join and a timeout as its waypoints`() {
        listOf(fork, crossing).forEach { diagram ->
            val automatic = StatechartLayouter().layout(diagram, text)

            val frozen = VisEditing.freeze(diagram, automatic)

            val bent = automatic.routes.filter { it.lineId != null && it.points.size > 2 }
            if (diagram == fork) assertTrue(bent.any { it.kind == RouteKind.OUT_OF_BAR }, "the automatic layout bends no line of the split")
            assertEquals(bent.mapNotNull { it.lineId }.toSet(), frozen.waypoints.keys)
            assertTrue(frozen.waypoints.keys.all { it in StatechartArrangement.lines(diagram) }, "${frozen.waypoints.keys}")
            val byHand = ManualStatechartLayout.of(diagram, frozen, text)
            bent.forEach { route ->
                val again = byHand.routes.single { it.lineId == route.lineId }
                assertEquals(frozen.waypoints.getValue(checkNotNull(route.lineId)), again.points.subList(1, again.points.size - 1))
            }
        }
    }

    @Test
    fun `pruning keeps what the text still has and drops the lines of states a split no longer names`() {
        val vis = VisLayout(
            bars = mapOf(split.id to DiagramPoint(0f, 0f)),
            waypoints = mapOf(
                split.lineTo("left") to listOf(DiagramPoint(10f, 10f)),
                split.lineTo("gone") to listOf(DiagramPoint(10f, 10f)),
                join.lineFrom("right") to listOf(DiagramPoint(20f, 20f)),
                split.id to listOf(DiagramPoint(30f, 30f)),
            ),
            labels = mapOf(split.id to DiagramPoint(10f, 0f)),
        )

        val pruned = VisEditing.prune(fork, vis)

        assertEquals(setOf(split.lineTo("left"), join.lineFrom("right")), pruned.waypoints.keys, "a split itself is no line")
        assertEquals(vis.bars, pruned.bars)
        assertEquals(vis.labels, pruned.labels)

        val timeouts = VisLayout(
            waypoints = mapOf("TIMEOUT:blinking" to listOf(DiagramPoint(0f, 0f)), "TIMEOUT:walking" to listOf(DiagramPoint(0f, 0f))),
            labels = mapOf("TIMEOUT:cars go" to DiagramPoint(0f, 10f), "TIMEOUT:walking" to DiagramPoint(0f, 10f)),
        )
        val kept = VisEditing.prune(crossing, timeouts)
        assertEquals(setOf("TIMEOUT:blinking"), kept.waypoints.keys, "a timeout to a missing state keeps its waypoints while it is fixed")
        assertEquals(setOf("TIMEOUT:cars go"), kept.labels.keys, "a state without a timeout has no caption")
    }

    @Test
    fun `moving a state with nested states takes the waypoints of the timeouts between them along`() {
        val vis = VisLayout(
            boxes = mapOf("green" to DiagramPoint(400f, 300f), "blinking" to DiagramPoint(400f, 500f), "cars wait" to DiagramPoint(100f, 300f)),
            waypoints = mapOf("TIMEOUT:green" to listOf(DiagramPoint(500f, 400f)), "TIMEOUT:cars wait" to listOf(DiagramPoint(200f, 400f))),
        )

        val moved = VisEditing.moveState(crossing, vis, "walking", DiagramPoint(40f, 20f))

        assertEquals(listOf(DiagramPoint(540f, 420f)), moved.waypoints["TIMEOUT:green"])
        assertEquals(listOf(DiagramPoint(200f, 400f)), moved.waypoints["TIMEOUT:cars wait"], "this timeout leaves the moved state from outside")
    }

    @Test
    fun `a renamed state keeps the lines of its split and its timeout, and so does a split that gets one more target`() {
        val vis = VisLayout(
            bars = mapOf(split.id to DiagramPoint(200f, 200f)),
            waypoints = mapOf(split.lineTo("left") to listOf(DiagramPoint(100f, 200f)), split.lineFrom("idle") to listOf(DiagramPoint(200f, 150f))),
        )
        val renamed = LampStateMachine.diagramOf(LampStateMachine.FORK.replace("\"left\"", "\"west\""))

        val followed = StatechartArrangement.followed(fork, renamed, vis)

        val after = renamed.transitions[0]
        assertEquals(DiagramPoint(200f, 200f), followed.bars[after.id])
        assertEquals(listOf(DiagramPoint(100f, 200f)), followed.waypoints[after.lineTo("west")])
        assertEquals(listOf(DiagramPoint(200f, 150f)), followed.waypoints[after.lineFrom("idle")])

        val wider = LampStateMachine.FORK.replace("STATE \"done\" { }", "STATE \"done\" { }\n        STATE \"extra\" { }")
        val before = LampStateMachine.diagramOf(wider)
        val source = before.transitions[0]
        val change = assertNotNull(StatechartEdits.addEnd(wider, before, source.id, "extra"))
        val more = LampStateMachine.diagramOf(change.edits.sortedByDescending { it.range.start }.fold(wider) { now, edit -> now.replaceRange(edit.range.start, edit.range.end, edit.newText) })
        val grown = more.transitions[0]
        val carried = StatechartArrangement.followed(before, more, VisEditing.prune(before, vis))
        assertEquals(DiagramPoint(200f, 200f), carried.bars[grown.id])
        assertEquals(listOf(DiagramPoint(100f, 200f)), carried.waypoints[grown.lineTo("left")])
        assertEquals(listOf(DiagramPoint(200f, 150f)), carried.waypoints[grown.lineFrom("idle")])
        assertNull(carried.waypoints[grown.lineTo("extra")])

        val timeout = VisLayout(waypoints = mapOf("TIMEOUT:green" to listOf(DiagramPoint(1f, 2f))), labels = mapOf("TIMEOUT:green" to DiagramPoint(0f, 10f)))
        val lime = LampStateMachine.diagramOf(LampStateMachine.CROSSING.replace("\"green\"", "\"lime\""))
        val kept = StatechartArrangement.followed(crossing, lime, timeout)
        assertEquals(listOf(DiagramPoint(1f, 2f)), kept.waypoints["TIMEOUT:lime"])
        assertEquals(DiagramPoint(0f, 10f), kept.labels["TIMEOUT:lime"])
    }

    @Test
    fun `the lines of splits, joins and timeouts are written to the file and read back, and a file of the old form still reads`() {
        val layout = VisLayout(
            bars = mapOf(split.id to DiagramPoint(200f, 230f)),
            waypoints = mapOf(split.lineTo("right") to listOf(DiagramPoint(300f, 230f)), "TIMEOUT:cars go" to listOf(DiagramPoint(10f, 20f))),
            labels = mapOf(split.id to DiagramPoint(20f, -10f), "TIMEOUT:cars go" to DiagramPoint(0f, 10f)),
        )

        val file = VisLayoutJson.encode(layout)

        assertTrue("\"SPLIT:idle->left,right#1/to:right\"" in file && "\"TIMEOUT:cars go\"" in file, file)
        assertEquals(layout, VisLayoutJson.decode(file))

        val old = """
            {
              "version": 2,
              "boxes": { "idle": { "x": 200, "y": 100 }, "left": { "x": 100, "y": 300 }, "right": { "x": 300, "y": 300 }, "done": { "x": 200, "y": 500 } },
              "bars": { "SPLIT:idle->left,right#1": { "x": 260, "y": 190 } },
              "lines": { "SPLIT:idle->left,right#1": { "label": { "x": 10, "y": 0 } } }
            }
        """.trimIndent()
        val read = assertNotNull(VisLayoutJson.decode(old))
        assertEquals(read, VisEditing.prune(fork, read), "nothing of the old form is dropped")
        val drawn = ManualStatechartLayout.of(fork, read, text)
        assertEquals(DiagramPoint(260f, 190f), drawn.bars.getValue(split.id).center)
        assertTrue(drawn.routes.filter { it.edgeId == split.id }.all { it.points.size == 2 }, "the lines of the split are straight")
    }
}
