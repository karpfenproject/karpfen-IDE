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
import io.karpfen.ide.notation.diagram.layout.DiagramBox
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.statechart.StatechartProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the changes a statechart makes to the text of its state machine. Every change is applied to the text, and the
 * new text is read again with the parser of the editor. It must read without syntax errors and give the diagram the
 * change was meant to give.
 */
class StatechartEditsTest {

    private val text = LampStateMachine.LAMP
    private val lamp = LampStateMachine.diagramOf(text)

    @Test
    fun `a new state goes to the end of the states and is drawn where the user asked`() {
        val change = assertNotNull(StatechartEdits.addState(text, lamp, null, "idle", initial = false, at = DiagramPoint(123f, 456f)))

        val after = applied(change)
        assertEquals(listOf("off", "on", "broken", "idle"), read(after).states.map { it.name })
        assertTrue("\n        STATE \"idle\" { }\n" in after, after)
        assertEquals(mapOf("idle" to DiagramPoint(120f, 460f)), assertNotNull(change.layout)(VisLayout()).boxes)
        assertNull(StatechartEdits.addState(text, lamp, null, "off", initial = false), "the name is taken")
        assertEquals("state", StatechartEdits.freshName(lamp))
        assertEquals("state 2", StatechartEdits.freshName(read(applied(assertNotNull(StatechartEdits.addState(text, lamp, null, "state", false))))))
    }

    @Test
    fun `a new inner state goes into its parent, also into a state that had none, which keeps its size`() {
        val nested = read(applied(assertNotNull(StatechartEdits.addState(text, lamp, "on", "blinking", initial = false))))
        assertEquals(listOf("full", "brighter", "blinking"), nested.state("on")?.children?.map { it.name })

        val frame = DiagramBox(0f, 0f, 80f, 36f)
        val change = assertNotNull(StatechartEdits.addState(text, lamp, "broken", "shards", initial = true, parentFrame = frame))
        val first = read(applied(change))
        assertEquals(listOf("shards"), first.state("broken")?.children?.map { it.name })
        assertTrue(first.state("shards")?.isInitial == true)
        assertEquals(mapOf("broken" to frame), assertNotNull(change.layout)(VisLayout()).frames)
    }

    @Test
    fun `a new transition is the last one, and its states must exist`() {
        val after = read(applied(assertNotNull(StatechartEdits.addTransition(text, lamp, "broken", "off"))))

        assertEquals("NORMAL:broken->off#1", after.transitions.last().id)
        assertEquals(lamp.transitions.size + 1, after.transitions.size)
        assertNull(StatechartEdits.addTransition(text, lamp, "broken", "nowhere"))
    }

    @Test
    fun `renaming a state renames it in its transitions and keeps its place and its lines in the layout`() {
        val change = assertNotNull(StatechartEdits.rename(text, lamp, "off", "dark"))

        val after = read(applied(change))
        assertEquals(listOf("dark", "on", "broken"), after.states.map { it.name })
        assertEquals(lamp.transitions.map { it.id.replace("off", "dark") }, after.transitions.map { it.id })
        val vis = VisLayout(
            boxes = mapOf("off" to DiagramPoint(10f, 20f), "full" to DiagramPoint(30f, 40f)),
            waypoints = mapOf("NORMAL:off->full#1" to listOf(DiagramPoint(1f, 2f))),
            labels = mapOf("NORMAL:on->off#1" to DiagramPoint(10f, 0f)),
        )
        assertEquals(
            VisLayout(
                boxes = mapOf("dark" to DiagramPoint(10f, 20f), "full" to DiagramPoint(30f, 40f)),
                waypoints = mapOf("NORMAL:dark->full#1" to listOf(DiagramPoint(1f, 2f))),
                labels = mapOf("NORMAL:on->dark#1" to DiagramPoint(10f, 0f)),
            ),
            assertNotNull(change.layout)(vis),
        )
        assertNull(StatechartEdits.rename(text, lamp, "off", "on"), "the name is taken")
        assertNull(StatechartEdits.rename(text, lamp, "off", "  "))
        assertNull(StatechartEdits.rename(text, lamp, "off", "off"))
    }

    @Test
    fun `deleting a state deletes its inner states and the transitions that name them`() {
        val after = read(applied(assertNotNull(StatechartEdits.deleteState(text, lamp, "on"))))

        assertEquals(listOf("off", "broken"), after.allStates().map { it.name })
        assertEquals(listOf("NORMAL:off->broken#1", "NORMAL:off->broken#2"), after.transitions.map { it.id })
    }

    @Test
    fun `deleting a transition leaves the others`() {
        val id = lamp.transitions.first().id
        val after = read(applied(assertNotNull(StatechartEdits.deleteTransition(text, lamp, id))))

        assertEquals(lamp.transitions.drop(1).map { it.label }, after.transitions.map { it.label })
        assertNull(StatechartEdits.deleteTransition(text, lamp, "NORMAL:a->b#9"))
    }

    @Test
    fun `making a state the initial one demotes the one that was`() {
        val after = read(applied(assertNotNull(StatechartEdits.makeInitial(text, lamp, "broken"))))

        assertEquals(listOf(false, false, true), after.states.map { it.isInitial })
        assertNull(StatechartEdits.makeInitial(text, lamp, "off"), "it is the initial state already")
        val inner = read(applied(assertNotNull(StatechartEdits.makeInitial(text, lamp, "brighter"))))
        assertTrue(inner.state("brighter")?.isInitial == true && inner.state("off")?.isInitial == true, "each level has its own")
    }

    @Test
    fun `a state moves out of its parent and into another state with everything in it`() {
        val out = read(applied(assertNotNull(StatechartEdits.moveTo(text, lamp, "brighter", null))))
        assertEquals(listOf("off", "on", "broken", "brighter"), out.states.map { it.name })
        assertEquals(listOf("full"), out.state("on")?.children?.map { it.name })
        assertTrue(out.state("brighter")?.hasEntry == true, "the state keeps its blocks")

        val into = read(applied(assertNotNull(StatechartEdits.moveTo(text, lamp, "on", "broken"))))
        assertEquals(listOf("off", "broken"), into.states.map { it.name })
        assertEquals(listOf("on", "full", "brighter"), into.state("broken")?.withDescendants()?.drop(1)?.map { it.name })

        assertNull(StatechartEdits.moveTo(text, lamp, "on", "full"), "a state cannot move into a state nested in it")
        assertNull(StatechartEdits.moveTo(text, lamp, "on", "on"))
        assertNull(StatechartEdits.moveTo(text, lamp, "full", "on"), "it is there already")
        assertNull(StatechartEdits.moveTo(text, lamp, "off", null), "it is at the top level already")
    }

    @Test
    fun `a state gets an entry block first and a do block after it`() {
        val withDo = applied(assertNotNull(StatechartEdits.addBlock(text, lamp, "off", StatechartEdits.DO)))
        val off = assertNotNull(read(withDo).state("off"))
        assertTrue(off.hasEntry && off.hasDo)
        assertTrue(assertNotNull(off.entryRange).end <= assertNotNull(off.doRange).start, "the do block comes after the entry block")

        val both = read(applied(assertNotNull(StatechartEdits.addBlock(text, lamp, "broken", StatechartEdits.ENTRY))))
        assertTrue(both.state("broken")?.hasEntry == true)
        assertNull(StatechartEdits.addBlock(text, lamp, "off", StatechartEdits.ENTRY), "it has one already")
    }

    @Test
    fun `the edits also work on a state machine written on few lines`() {
        val small = """STATEMACHINE ATTACHED TO "T" { STATES { INITIAL STATE "a" { } } TRANSITIONS { } }"""
        var now = small
        fun step(change: (String, StatechartDiagram) -> DiagramChange?) { now = applied(assertNotNull(change(now, read(now))), now) }

        step { t, d -> StatechartEdits.addState(t, d, null, "b", initial = false) }
        step { t, d -> StatechartEdits.addState(t, d, "a", "inner", initial = false) }
        step { t, d -> StatechartEdits.addTransition(t, d, "a", "b") }
        step { t, d -> StatechartEdits.addBlock(t, d, "b", StatechartEdits.ENTRY) }
        step { t, d -> StatechartEdits.addBlock(t, d, "b", StatechartEdits.DO) }
        step { t, d -> StatechartEdits.moveTo(t, d, "inner", null) }
        step { t, d -> StatechartEdits.rename(t, d, "inner", "c") }

        val result = read(now)
        assertEquals(listOf("a", "b", "c"), result.states.map { it.name })
        assertEquals(listOf("NORMAL:a->b#1"), result.transitions.map { it.id })
        assertTrue(result.state("b")?.hasEntry == true && result.state("b")?.hasDo == true)
    }

    /** [source] with the edits of [change] applied. */
    private fun applied(change: DiagramChange, source: String = text): String =
        change.edits.sortedByDescending { it.range.start }.fold(source) { now, edit -> now.replaceRange(edit.range.start, edit.range.end, edit.newText) }

    /** The diagram of [source], which must read without syntax errors. */
    private fun read(source: String): StatechartDiagram {
        val outcome = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES).parser.parse(TextSnapshot(source, 0))
        assertTrue(outcome.isSyntaxClean, "the text has syntax errors:\n$source")
        return assertNotNull(StatechartProjection.of(source, outcome.outline))
    }
}
