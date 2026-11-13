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
package io.karpfen.ide.notation.diagram.edit

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.notation.diagram.LampStateMachine
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.ManualStatechartLayout
import io.karpfen.ide.notation.diagram.layout.RouteKind
import io.karpfen.ide.notation.diagram.layout.StatechartMetrics
import io.karpfen.ide.notation.diagram.layout.TextMeasurer
import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests what a statechart offers and changes at the line of a timeout. Every change is applied to the text, and the
 * new text is read again with the parser of the editor, which must find no syntax error in it.
 */
class TimeoutEditsTest {

    private val text = LampStateMachine.CROSSING
    private val crossing = LampStateMachine.diagramOf(text)
    private val layout = ManualStatechartLayout.of(
        crossing,
        VisLayout(
            boxes = mapOf(
                "cars go" to DiagramPoint(100f, 100f),
                "cars wait" to DiagramPoint(100f, 300f),
                "green" to DiagramPoint(400f, 300f),
                "blinking" to DiagramPoint(400f, 500f),
            ),
        ),
        TextMeasurer.estimating(),
    )

    @Test
    fun `a timeout leads to another state, and only the name of its target changes`() {
        val after = applied(assertNotNull(TimeoutEdits.retarget(text, crossing, "cars go", "walking")))

        assertTrue("TICK LIMIT { DELAY: 2 TICKS TIMEOUT: AFTER 10 TICKS TRANSITION TO \"walking\" }" in after, after)
        assertEquals(listOf(2L, 10L, "walking"), read(after).state("cars go")?.let { listOf(it.delay, it.timeout?.ticks, it.timeout?.target) })
        assertNull(TimeoutEdits.retarget(text, crossing, "cars go", "cars wait"), "it leads there already")
        assertNull(TimeoutEdits.retarget(text, crossing, "cars go", "nowhere"), "the state does not exist")
        assertNull(TimeoutEdits.retarget(text, crossing, "walking", "green"), "the state has no timeout")
    }

    @Test
    fun `removing a timeout keeps the delay, and a tick limit without a delay goes as a whole`() {
        val kept = read(applied(assertNotNull(TimeoutEdits.remove(text, crossing, "cars go"))))
        assertEquals(listOf(2L, null), kept.state("cars go")?.let { listOf(it.delay, it.timeout) })
        assertTrue(kept.state("cars go")?.hasTickLimit == true)

        val gone = applied(assertNotNull(TimeoutEdits.remove(text, crossing, "cars wait")))
        assertNull(read(gone).state("cars wait")?.tickLimitRange)
        assertTrue("STATE \"cars wait\" {\n        }" in gone, gone)
        assertNull(TimeoutEdits.remove(text, crossing, "walking"))
    }

    @Test
    fun `renaming a state renames it in the timeouts that lead to it, and the line of its own timeout keeps its place`() {
        val change = assertNotNull(StatechartEdits.rename(text, crossing, "cars wait", "cars stop"))

        val after = read(applied(change))
        assertEquals("cars stop", after.state("cars go")?.timeout?.target)
        assertEquals(listOf("TIMEOUT:cars go", "TIMEOUT:cars stop", "TIMEOUT:green", "TIMEOUT:blinking"), after.timeouts().map { it.id })
        val vis = VisLayout(
            waypoints = mapOf("TIMEOUT:cars wait" to listOf(DiagramPoint(1f, 2f)), "SPLIT:a->cars wait#1/to:cars wait" to listOf(DiagramPoint(3f, 4f))),
            labels = mapOf("TIMEOUT:cars wait" to DiagramPoint(0f, 10f)),
        )
        assertEquals(
            VisLayout(
                waypoints = mapOf("TIMEOUT:cars stop" to listOf(DiagramPoint(1f, 2f)), "SPLIT:a->cars stop#1/to:cars stop" to listOf(DiagramPoint(3f, 4f))),
                labels = mapOf("TIMEOUT:cars stop" to DiagramPoint(0f, 10f)),
            ),
            assertNotNull(change.layout)(vis),
        )
    }

    @Test
    fun `the line and the caption of a timeout offer its tick limit, another target and its removal, and nothing of a transition`() {
        val line = layout.routes.single { it.kind == RouteKind.TIMEOUT && it.edgeId == "TIMEOUT:cars go" }
        val onLine = DiagramPoint(line.points.first().x, (line.points.first().y + line.points.last().y) / 2f)
        val onCaption = assertNotNull(line.label).center

        val all = StatechartActions(KstatesFeature.entries.toSet())
        listOf(onLine, onCaption).forEach { point ->
            val actions = all.at(text, crossing, layout, point)
            assertEquals(listOf("Edit tick limit as text", "Lead to another state", "Remove timeout"), actions.map { it.label }, "at $point")
        }
        val open = all.at(text, crossing, layout, onLine).first() as DiagramAction.Open
        val marker = StatechartCells.of(text, crossing, layout).single { it.key == "cars go.${StatechartMetrics.LIMIT_WORD}" }
        assertEquals(marker.key to marker.code, open.cell.key to open.cell.code, "the same cell as the marker of the tick limit")
        val pick = all.at(text, crossing, layout, onLine)[1] as DiagramAction.Pick
        assertEquals(setOf("cars go", "walking", "green", "blinking"), pick.candidates)
        val change = assertNotNull((pick.next("green") as DiagramAction.Change).change())
        assertEquals("green", read(applied(change)).state("cars go")?.timeout?.target)

        val off = StatechartActions(emptySet()).at(text, crossing, layout, onLine).map { it.label }
        assertEquals(listOf("Edit tick limit as text", "Remove timeout"), off, "a tick limit is not written while the feature is off")
    }

    @Test
    fun `a timeout has no cell of its own on its caption`() {
        val keys = StatechartCells.of(text, crossing, layout).map { it.key }

        assertTrue(keys.none { it.startsWith("TIMEOUT") }, "$keys")
        assertEquals(crossing.transitions.size, keys.count { it.startsWith("NORMAL") })
    }

    /** The crossing with the edits of [change] applied. */
    private fun applied(change: DiagramChange): String =
        change.edits.sortedByDescending { it.range.start }.fold(text) { now, edit -> now.replaceRange(edit.range.start, edit.range.end, edit.newText) }

    /** The diagram of [source], which must read without syntax errors. */
    private fun read(source: String): StatechartDiagram = LampStateMachine.diagramOf(source)
}
