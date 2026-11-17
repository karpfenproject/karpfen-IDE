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

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.notation.diagram.LampStateMachine
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the edits of the pages of a state machine. Every change is applied to the text, and the new text is read again
 * with the parser of the editor, which must find no syntax error in it.
 */
class PageEditsTest {

    private val paged = LampStateMachine.PAGED
    private val lamp = LampStateMachine.diagramOf(paged)
    private val fragmentText = LampStateMachine.BULB_TEST
    private val fragment = LampStateMachine.diagramOf(fragmentText)

    @Test
    fun `a container state is added without a fragment at the top level or inside a state, never inside a container state`() {
        val top = applied(paged, assertNotNull(PageEdits.addContainer(paged, lamp, null, "second test")))
        val inner = read(applied(paged, assertNotNull(PageEdits.addContainer(paged, lamp, "on", "inner"))))

        assertTrue("        CONTAINER STATE \"second test\" { }\n" in top, top)
        assertEquals("", read(top).state("second test")?.fragment)
        assertEquals(listOf("check", "inner"), inner.state("on")?.children?.map { it.name })
        assertEquals("", inner.state("inner")?.fragment)
        assertNull(PageEdits.addContainer(paged, lamp, "self test", "inner"))
        assertNull(PageEdits.addContainer(paged, lamp, null, "off"), "the name is taken")
    }

    @Test
    fun `a final state is added at the top level of a fragment only`() {
        val after = read(applied(fragmentText, assertNotNull(PageEdits.addFinal(fragmentText, fragment, "aborted"))))

        assertTrue(after.state("aborted")!!.isFinal)
        assertNull(PageEdits.addFinal(paged, lamp, "aborted"))
    }

    @Test
    fun `a container state uses another fragment, and one without USE gets it behind its name`() {
        val after = applied(paged, assertNotNull(PageEdits.useFragment(paged, lamp, "self test", "switch test")))
        assertEquals("switch test", read(after).state("self test")?.fragment)
        assertTrue("CONTAINER STATE \"self test\" USE \"switch test\" {" in after, after)
        assertNull(PageEdits.useFragment(paged, lamp, "self test", "bulb test"), "it uses it already")
        assertNull(PageEdits.useFragment(paged, lamp, "off", "bulb test"), "no container state")

        val bare = paged.replace("CONTAINER STATE \"check\" USE \"switch test\"", "CONTAINER STATE \"check\"")
        val written = applied(bare, assertNotNull(PageEdits.useFragment(bare, read(bare), "check", " any \"name\" ")))
        assertTrue("INITIAL CONTAINER STATE \"check\" USE \"any 'name'\" { }" in written, written)
    }

    @Test
    fun `an empty name takes USE out, and a state without USE stays as it is`() {
        val after = applied(paged, assertNotNull(PageEdits.useFragment(paged, lamp, "self test", "  ")))

        assertTrue("SHALLOW HISTORY CONTAINER STATE \"self test\" {" in after, after)
        assertEquals("", read(after).state("self test")?.fragment)
        val bare = paged.replace("CONTAINER STATE \"check\" USE \"switch test\"", "CONTAINER STATE \"check\"")
        assertNull(PageEdits.useFragment(bare, read(bare), "check", ""))
    }

    @Test
    fun `a state becomes a container state without a fragment and keeps its mark, its history, its breakpoints and its tick limit`() {
        val text = paged.replace(
            "INITIAL STATE \"off\" { }",
            "INITIAL DEEP HISTORY STATE \"off\" {\n            BREAKPOINT { ENTRY }\n            TICK LIMIT { DELAY: 2 TICKS }\n        }",
        )
        val after = applied(text, assertNotNull(PageEdits.makeContainer(text, read(text), "off")))

        assertTrue(
            "INITIAL DEEP HISTORY CONTAINER STATE \"off\" {\n            BREAKPOINT { ENTRY }\n            TICK LIMIT { DELAY: 2 TICKS }\n        }" in after,
            after,
        )
        assertEquals(text, after.replace("CONTAINER STATE \"off\"", "STATE \"off\""), "nothing else changes")
        assertEquals("", read(after).state("off")?.fragment)
    }

    @Test
    fun `a container state without a fragment is the error of dsl-tools until one is set`() {
        val made = applied(paged, assertNotNull(PageEdits.makeContainer(paged, lamp, "off")))

        assertTrue(DslReader().kstates(TextSnapshot(made, 0)).diagnostics.any { it.code == "kstates.container-use" })
        val wired = applied(made, assertNotNull(PageEdits.useFragment(made, read(made), "off", "wiring")))
        assertTrue("INITIAL CONTAINER STATE \"off\" USE \"wiring\" { }" in wired, wired)
        assertTrue(DslReader().kstates(TextSnapshot(wired, 0)).diagnostics.none { it.code == "kstates.container-use" })
    }

    @Test
    fun `only a state without actions and inner states that is no container or final state becomes a container state`() {
        val acting = paged.replace("INITIAL STATE \"off\" { }", "INITIAL STATE \"off\" { ENTRY { } }")

        assertNull(PageEdits.makeContainer(acting, read(acting), "off"), "it has actions")
        assertNull(PageEdits.makeContainer(paged, lamp, "on"), "it has inner states")
        assertNull(PageEdits.makeContainer(paged, lamp, "self test"), "it is a container state")
        assertNull(PageEdits.makeContainer(fragmentText, fragment, "passed"), "it is a final state")
    }

    @Test
    fun `a container state becomes a plain state without its history and without the DONE of what leaves it`() {
        val after = applied(paged, assertNotNull(PageEdits.makePlain(paged, lamp, "self test")))
        val plain = read(after)

        assertTrue("        STATE \"self test\" {\n            TICK LIMIT" in after, after)
        assertNull(plain.state("self test")?.fragment)
        assertNull(plain.transitions[1].conditionRange, "the condition held DONE alone")
        assertEquals("switch/press", plain.transitions[2].label)
        assertTrue(plain.transitions[3].isDone, "it does not leave the state")
        assertNull(PageEdits.makePlain(paged, lamp, "off"), "no container state")

        val inner = applied(paged, assertNotNull(PageEdits.makePlain(paged, lamp, "check")))
        assertTrue("            INITIAL STATE \"check\" { }" in inner, inner)
    }

    @Test
    fun `making a state plain leaves no error that the edit caused`() {
        val text = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    SHALLOW HISTORY CONTAINER STATE "bulb check" USE "bulb test" { }
                    CONTAINER STATE "switch check" USE "switch test" { }
                    CONTAINER STATE "single" USE "bulb test" { }
                    STATE "checked" { }
                }
                TRANSITIONS {
                    SPLIT "off" -> "bulb check", "switch check" { }
                    JOIN "bulb check", "switch check" -> "checked" { CONDITION { DONE } }
                    JOIN "single", "off" -> "checked" { CONDITION { DONE EVENT("switch", "press") } }
                    SPLIT "single" -> "off", "checked" { CONDITION { DONE } }
                }
            }
        """.trimIndent()
        val diagram = read(text)
        assertEquals(emptyList(), errorsOf(text))

        val bulb = applied(text, assertNotNull(PageEdits.makePlain(text, diagram, "bulb check")))
        assertEquals(emptyList(), errorsOf(bulb), bulb)
        assertTrue(read(bulb).transitions[1].isDone, "the join keeps DONE for its other container state")
        assertTrue("        STATE \"bulb check\" { }" in bulb, bulb)

        val single = applied(text, assertNotNull(PageEdits.makePlain(text, diagram, "single")))
        assertEquals(emptyList(), errorsOf(single), single)
        assertEquals(listOf(true, false, false), read(single).transitions.drop(1).map { it.isDone })
    }

    @Test
    fun `DONE is offered only where the language takes it`() {
        val ids = lamp.transitions.map { it.id }

        assertEquals(listOf(false, true, true, false), ids.map { PageEdits.allowsDone(lamp, it) })
    }

    @Test
    fun `DONE goes in front of the clauses of a condition, or makes a condition of its own`() {
        val press = lamp.transitions[2]
        val onOneLine = applied(paged, assertNotNull(PageEdits.addDone(paged, lamp, press.id)))
        assertTrue("CONDITION { DONE EVENT(\"switch\", \"press\") }" in onOneLine, onOneLine)
        assertEquals("DONE & switch/press", read(onOneLine).transitions[2].label)

        val plain = paged.replace("TRANSITION \"self test\" -> \"off\" { CONDITION { EVENT(\"switch\", \"press\") } }", "TRANSITION \"self test\" -> \"off\" { }")
        val made = applied(plain, assertNotNull(PageEdits.addDone(plain, read(plain), press.id)))
        assertEquals("DONE", read(made).transitions[2].label)

        assertNull(PageEdits.addDone(paged, lamp, lamp.transitions[1].id), "it has DONE already")
        assertNull(PageEdits.addDone(paged, lamp, lamp.transitions[0].id), "it leaves no container state")
    }

    @Test
    fun `removing DONE takes the whole condition if nothing else is in it, and only the word otherwise`() {
        val alone = applied(paged, assertNotNull(PageEdits.removeDone(paged, lamp, lamp.transitions[1].id)))
        assertNull(read(alone).transitions[1].conditionRange)

        val beside = applied(paged, assertNotNull(PageEdits.removeDone(paged, lamp, lamp.transitions[3].id)))
        val transition = read(beside).transitions[3]
        assertEquals("switch/press", transition.label)
        assertTrue(beside.lines().none { it.isBlank() && it.isNotEmpty() } && "DONE\n" !in beside, beside)

        assertNull(PageEdits.removeDone(paged, lamp, lamp.transitions[0].id))
    }

    /** [text] with the edits of [change], applied from the end so the earlier ranges stay valid. */
    private fun applied(text: String, change: DiagramChange): String =
        change.edits.sortedByDescending { it.range.start }.fold(text) { now, edit -> now.replaceRange(edit.range.start, edit.range.end, edit.newText) }

    /** The messages of the errors that the reader of dsl-tools finds in [source], all language features on. */
    private fun errorsOf(source: String): List<String> =
        DslReader().kstates(TextSnapshot(source, 0)).diagnostics.filter { it.severity == Severity.ERROR }.map { it.message }

    /** The diagram of [source], which must read without syntax errors. */
    private fun read(source: String): StatechartDiagram = LampStateMachine.diagramOf(source)
}
