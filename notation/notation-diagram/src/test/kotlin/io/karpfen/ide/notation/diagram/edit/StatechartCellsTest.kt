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

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.notation.diagram.LampStateMachine
import io.karpfen.ide.notation.diagram.layout.StatechartLayouter
import io.karpfen.ide.notation.diagram.layout.TextMeasurer
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.statechart.StatechartProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the code cells of a statechart: which there are, what code they show, where their markers stand, and that
 * saving edited code gives a text that the parser of the editor reads without syntax errors.
 */
class StatechartCellsTest {

    private val text = LampStateMachine.LAMP
    private val lamp = LampStateMachine.diagramOf(text)
    private val layout = StatechartLayouter().layout(lamp, TextMeasurer.estimating())
    private val cells = StatechartCells.of(text, lamp, layout)

    @Test
    fun `every entry block and every transition has a cell with its code`() {
        assertEquals(
            listOf("off.entry", "on.entry", "brighter.entry") + lamp.transitions.map { it.id },
            cells.map { it.key },
        )
        assertEquals("SET(\"mode\", \"off\")", cell("off.entry").code)
        assertEquals("entry", cell("off.entry").word)
        assertEquals("EVENT(\"switch\", \"press\")", cell("NORMAL:off->full#1").code)
        assertEquals(
            "EVENT(\"switch\", \"dim\")\nVALUE(\"event->bright\")\nEVAL { return \$(bulb->watts) + 10 <= \$(bulb->maxWatts) }",
            cell("NORMAL:on->brighter#1").code,
        )
        assertEquals("", cell("NORMAL:off->broken#2").code, "a transition without a condition has an empty cell")
    }

    @Test
    fun `the marker of a state lies in its box and the marker of a transition in its label`() {
        val off = layout.states.getValue("off")
        val entry = cell("off.entry").box
        assertTrue(entry.x >= off.x && entry.right <= off.right && entry.y >= off.y && entry.bottom <= off.bottom, "$entry is outside $off")

        val on = layout.states.getValue("on")
        val header = cell("on.entry").box
        assertTrue(header.y >= on.y && header.bottom <= on.y + layout.headerHeight, "$header is not in the row of the name of $on")

        val label = assertNotNull(layout.routes.single { it.transitionId == "NORMAL:off->full#1" }.label)
        val mark = cell("NORMAL:off->full#1").box
        assertTrue(mark.x >= label.x && mark.right <= label.right && mark.y >= label.y && mark.bottom <= label.bottom, "$mark is outside $label")
    }

    @Test
    fun `saving the code of a block replaces what stands between its braces`() {
        val one = read(applied(assertNotNull(cell("off.entry").save("SET(\"mode\", \"dark\")"))))
        assertEquals("SET(\"mode\", \"dark\")", cellOf(one, "off.entry").code)

        val several = applied(assertNotNull(cell("off.entry").save("SET(\"mode\", \"off\")\nSET(\"bulb->lit\", \"false\")\n")))
        assertTrue("            ENTRY {\n                SET(\"mode\", \"off\")\n                SET(\"bulb->lit\", \"false\")\n            }\n" in several, several)
        assertEquals("SET(\"mode\", \"off\")\nSET(\"bulb->lit\", \"false\")", cellOf(read(several), "off.entry").code)

        val empty = read(applied(assertNotNull(cell("off.entry").save("  \n"))))
        assertTrue(empty.state("off")?.hasEntry == true, "an entry block may be empty")
        assertNull(cell("off.entry").save("SET(\"mode\", \"off\")"), "the same code changes nothing")
    }

    @Test
    fun `a condition is written, replaced and taken out again`() {
        val added = applied(assertNotNull(cell("NORMAL:off->broken#2").save("EVENT(\"service\", \"drop\")")))
        assertEquals("EVENT(\"service\", \"drop\")", cellOf(read(added), "NORMAL:off->broken#2").code)
        assertTrue("        TRANSITION \"off\" -> \"broken\" {\n            CONDITION { EVENT(\"service\", \"drop\") }\n        }\n" in added, added)

        val replaced = read(applied(assertNotNull(cell("NORMAL:off->full#1").save("EVENT(\"switch\", \"flip\")"))))
        assertEquals("switch/flip", replaced.transitions.first().label)

        val removed = read(applied(assertNotNull(cell("NORMAL:off->full#1").save(""))))
        assertNull(removed.transitions.first().conditionRange)
        assertNull(cell("NORMAL:off->broken#2").save(" "), "no code for a transition without a condition changes nothing")
    }

    private fun cell(key: String): DiagramCell = cells.single { it.key == key }

    private fun cellOf(diagram: StatechartDiagram, key: String): DiagramCell =
        StatechartCells.of(source(diagram), diagram, StatechartLayouter().layout(diagram, TextMeasurer.estimating())).single { it.key == key }

    private val sources = HashMap<StatechartDiagram, String>()

    private fun source(diagram: StatechartDiagram): String = sources.getValue(diagram)

    private fun applied(change: DiagramChange): String =
        change.edits.sortedByDescending { it.range.start }.fold(text) { now, edit -> now.replaceRange(edit.range.start, edit.range.end, edit.newText) }

    /** The diagram of [source], which must read without syntax errors. */
    private fun read(source: String): StatechartDiagram {
        val outcome = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES).parser.parse(TextSnapshot(source, 0))
        assertTrue(outcome.isSyntaxClean, "the text has syntax errors:\n$source")
        return assertNotNull(StatechartProjection.of(source, outcome.outline)).also { sources[it] = source }
    }
}
