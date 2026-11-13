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
package io.karpfen.ide.notation.diagram.text

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.notation.diagram.LampStateMachine
import io.karpfen.ide.notation.diagram.edit.ConstructCells
import io.karpfen.ide.notation.diagram.edit.DiagramCell
import io.karpfen.ide.notation.diagram.edit.DiagramChange
import io.karpfen.ide.notation.diagram.edit.StatechartCells
import io.karpfen.ide.notation.diagram.edit.StatechartEdits
import io.karpfen.ide.notation.diagram.layout.StatechartLayouter
import io.karpfen.ide.notation.diagram.layout.StatechartMetrics
import io.karpfen.ide.notation.diagram.layout.TextMeasurer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the frame of a code cell: the whole text it gives is what saving the code writes, every offset of the code
 * maps into that text and back, and ranges of the whole text map onto the code clipped or not at all.
 */
class CellFrameTest {

    @Test
    fun `a block with nested lines and blank lines is framed as saving writes it`() {
        val code = "\n  \nSET(\"mode\", \"off\")\n\n    SET(\"bulb->lit\", \"false\")  \n   \n\tSET(\"x\", \"1\")\n\n"
        val frame = framed(LampStateMachine.LAMP, cell(LampStateMachine.LAMP, "off.entry"), code)
        assertTrue("                SET(\"mode\", \"off\")\n\n                    SET(\"bulb->lit\", \"false\")  \n\n                \tSET(\"x\", \"1\")\n            }" in frame.whole, frame.whole)
    }

    @Test
    fun `a block of one line is framed without the whitespace around its code`() {
        val frame = framed(LampStateMachine.LAMP, cell(LampStateMachine.LAMP, "off.entry"), "   SET(\"mode\", \"dark\")  ")
        assertTrue("ENTRY { SET(\"mode\", \"dark\") }" in frame.whole, frame.whole)
        assertEquals(TextRange(3, 6), frame.toPiece(TextRange(frame.region.start - 2, frame.region.start + 3)), "the spaces in front are left out")
    }

    @Test
    fun `an empty block is framed at the place between its braces`() {
        val code = "  \n "
        val frame = framed(LampStateMachine.LAMP, cell(LampStateMachine.LAMP, "off.entry"), code)
        assertTrue(frame.region.isEmpty)
        assertEquals("}", frame.whole.substring(frame.region.start, frame.region.start + 1))
        assertEquals(TextRange.empty(code.length), frame.toPiece(TextRange.empty(frame.region.start)))
    }

    @Test
    fun `the first condition of a transition is framed in the block that saving makes`() {
        val cell = cell(LampStateMachine.LAMP, "NORMAL:off->broken#2")
        val one = framed(LampStateMachine.LAMP, cell, "EVENT(\"service\", \"drop\")")
        assertTrue("TRANSITION \"off\" -> \"broken\" {\n            CONDITION { EVENT(\"service\", \"drop\") }\n        }" in one.whole, one.whole)
        framed(LampStateMachine.LAMP, cell, "EVENT(\"service\", \"drop\")\n  VALUE(\"event->bright\")")
        assertNull(cell.frame(" \n"), "a blank condition of a transition without one has no place")
        assertNull(cell(LampStateMachine.LAMP, "NORMAL:off->full#1").frame(""), "a blank condition is taken out")
    }

    @Test
    fun `a tick limit is framed in its block and has no place once it is blank`() {
        val crossing = LampStateMachine.CROSSING
        val limit = cell(crossing, "cars go.${StatechartMetrics.LIMIT_WORD}")
        framed(crossing, limit, "DELAY: 2 TICKS\nTIMEOUT: AFTER 7 TICKS TRANSITION TO \"cars wait\"")
        framed(crossing, limit, "DELAY: 3 TICKS")
        assertNull(limit.frame("   "), "a blank tick limit is taken out of its state")
    }

    @Test
    fun `code in a text indented with tabs or deeper is framed with that indentation`() {
        val tabbed = LampStateMachine.LAMP.replace("    ", "\t")
        val frame = framed(tabbed, cell(tabbed, "brighter.entry"), "SET(\"a\", \"1\")\n  SET(\"b\", \"2\")")
        assertTrue("\t\t\t\t    SET(\"a\", \"1\")\n\t\t\t\t      SET(\"b\", \"2\")\n\t\t\t\t}" in frame.whole, frame.whole)

        val diagram = LampStateMachine.diagramOf(tabbed)
        val state = assertNotNull(diagram.state("on"))
        val construct = ConstructCells.of("on", "State on", tabbed, state.range)
        framed(tabbed, construct, construct.code)
        framed(tabbed, construct, construct.code.replace("STATE \"full\" { }", "STATE \"full\" {\n\tENTRY { }\n}\n\n"))
    }

    @Test
    fun `macros are framed where saving appends the block that does not exist yet`() {
        val lamp = LampStateMachine.LAMP
        val macros = assertNotNull(StatechartEdits.macros(lamp, LampStateMachine.diagramOf(lamp)))
        val frame = framed(lamp, macros, "MACRO \"dim\" {\n    SET(\"mode\", \"dim\")\n}")
        assertTrue("    MACROS {\n        MACRO \"dim\" {\n            SET(\"mode\", \"dim\")\n        }\n    }\n}" in frame.whole, frame.whole)
        assertNull(macros.frame(""), "no code and no block write nothing")
    }

    /**
     * The frame of [code] in [cell] of [source]. It checks that its whole text is what saving writes, that every
     * offset of [code] maps into the region of the frame and back onto itself, and how ranges at the borders map.
     */
    private fun framed(source: String, cell: DiagramCell, code: String): CellFrame {
        val frame = assertNotNull(cell.frame(code), "the cell has a frame")
        assertEquals(cell.save(code)?.let { applied(source, it) } ?: source, frame.whole, "the whole text is what saving writes")
        for (offset in 0..code.length) {
            val at = frame.toWhole(offset)
            assertTrue(at in frame.region.start..frame.region.end, "offset $offset lands at $at outside ${frame.region}")
            val back = assertNotNull(frame.toPiece(TextRange.empty(at)), "offset $offset maps back")
            assertEquals(at, frame.toWhole(back.start), "the place of offset $offset maps onto itself")
            if (offset < code.length && !code[offset].isWhitespace()) {
                assertEquals(code[offset], frame.whole[at], "the character at offset $offset")
                assertEquals(offset, back.start, "offset $offset maps back onto itself")
            }
        }
        val region = frame.region
        if (region.start > 0) assertNull(frame.toPiece(TextRange(0, region.start)), "a range before the code is dropped")
        if (region.end < frame.whole.length) assertNull(frame.toPiece(TextRange(region.end, frame.whole.length)), "a range after the code is dropped")
        if (!region.isEmpty) {
            val first = assertNotNull(frame.toPiece(TextRange.empty(region.start))).start
            val last = assertNotNull(frame.toPiece(TextRange.empty(region.end))).start
            assertEquals(TextRange(first, first + 1), frame.toPiece(TextRange(maxOf(0, region.start - 1), region.start + 1)), "clipped at the start")
            assertEquals(TextRange(last - 1, last), frame.toPiece(TextRange(region.end - 1, minOf(frame.whole.length, region.end + 1))), "clipped at the end")
            assertEquals(TextRange(first, last), frame.toPiece(TextRange(0, frame.whole.length)), "the whole text gives the whole code")
        }
        return frame
    }

    /** The cell with [key] of the statechart of [source]. */
    private fun cell(source: String, key: String): DiagramCell {
        val diagram = LampStateMachine.diagramOf(source)
        val layout = StatechartLayouter().layout(diagram, TextMeasurer.estimating())
        return StatechartCells.of(source, diagram, layout).single { it.key == key }
    }

    private fun applied(source: String, change: DiagramChange): String =
        change.edits.sortedByDescending { it.range.start }.fold(source) { now, edit -> now.replaceRange(edit.range.start, edit.range.end, edit.newText) }
}
