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
    fun `a container state is added at the top level or inside a state, never inside a container state`() {
        val top = read(applied(paged, assertNotNull(PageEdits.addContainer(paged, lamp, null, "second test", "bulb test"))))
        val inner = read(applied(paged, assertNotNull(PageEdits.addContainer(paged, lamp, "on", "inner", "switch test"))))

        assertEquals("bulb test", top.state("second test")?.fragment)
        assertEquals(listOf("check", "inner"), inner.state("on")?.children?.map { it.name })
        assertEquals("switch test", inner.state("inner")?.fragment)
        assertNull(PageEdits.addContainer(paged, lamp, "self test", "inner", "switch test"))
        assertNull(PageEdits.addContainer(paged, lamp, null, "off", "bulb test"), "the name is taken")
        assertNull(PageEdits.addContainer(paged, lamp, null, "free", " "))
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
        val written = applied(bare, assertNotNull(PageEdits.useFragment(bare, read(bare), "check", "bulb test")))
        assertTrue("INITIAL CONTAINER STATE \"check\" USE \"bulb test\" { }" in written, written)
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

    /** The diagram of [source], which must read without syntax errors. */
    private fun read(source: String): StatechartDiagram = LampStateMachine.diagramOf(source)
}
