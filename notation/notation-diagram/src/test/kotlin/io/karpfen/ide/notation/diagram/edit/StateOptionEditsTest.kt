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
import io.karpfen.ide.notation.diagram.layout.DiagramBox
import io.karpfen.ide.notation.diagram.layout.StatechartLayout
import io.karpfen.ide.notation.diagram.layout.StatechartMetrics
import io.karpfen.ide.notation.diagram.statechart.StateHistory
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests what a statechart reads and changes of the histories, tick limits and breakpoints of a state machine. Every
 * change is applied to the text, and the new text is read again with the parser of the editor, which must find no
 * syntax error in it.
 */
class StateOptionEditsTest {

    private val crossing = """
        STATEMACHINE ATTACHED TO "Crossing" {
            STATES {
                INITIAL DEEP HISTORY STATE "operating" {
                    // the cars
                    BREAKPOINT { EXIT }
                    STATE "cars go" {
                        TICK LIMIT { DELAY: 5 TICKS }
                        ENTRY { SET("light", "green") }
                    }
                    STATE "cars wait" {
                        BREAKPOINT { ENTRY EXIT }
                        TICKS LIMIT {
                            TIMEOUT: AFTER 10 TICKS TRANSITION TO "cars go"
                            DELAY: 1 TICK
                        }
                    }
                }
                SHALLOW HISTORY STATE "maintenance" {
                    STATE "testing" { }
                }
                STATE "plain" { }
            }
            TRANSITIONS {
                TRANSITION "cars go" -> "cars wait" { CONDITION { EVENT("public", "pedestrian") } }
                TRANSITION "cars wait" -> "cars go" NOT LOOPING {
                    BREAKPOINT { START END }
                    CONDITION { EVENT("public", "crossed") }
                }
                TRANSITION "operating" -> "maintenance" { BREAKPOINT { END } }
            }
        }
    """.trimIndent()

    private val diagram = LampStateMachine.diagramOf(crossing)

    @Test
    fun `the diagram reads the history, the breakpoints and the tick limit of each state and not those of nested states`() {
        val operating = assertNotNull(diagram.state("operating"))
        assertEquals(StateHistory.DEEP, operating.history)
        assertEquals(false to true, operating.entryBreakpoint to operating.exitBreakpoint)
        assertNull(operating.tickLimitRange, "the tick limit of a nested state is not the one of its parent")
        assertTrue(operating.isInitial)

        val go = assertNotNull(diagram.state("cars go"))
        assertEquals(listOf(StateHistory.NONE, 5L, null, false, false), listOf(go.history, go.delay, go.timeout, go.entryBreakpoint, go.exitBreakpoint))
        assertEquals("TICK LIMIT { DELAY: 5 TICKS }", crossing.substring(go.tickLimitRange!!.start, go.tickLimitRange!!.end))

        val wait = assertNotNull(diagram.state("cars wait"))
        assertEquals(listOf(1L, 10L to "cars go", true, true), listOf(wait.delay, wait.timeout?.let { it.ticks to it.target }, wait.entryBreakpoint, wait.exitBreakpoint))
        assertEquals("TIMEOUT: AFTER 10 TICKS TRANSITION TO \"cars go\"", wait.timeout?.range?.let { crossing.substring(it.start, it.end) })
        assertEquals("cars go", wait.timeout?.targetRange?.let { crossing.substring(it.start, it.end) })
        assertEquals(StateHistory.SHALLOW, diagram.state("maintenance")?.history)
        assertEquals(StateHistory.NONE, diagram.state("plain")?.history)
        assertEquals(
            listOf(false to false, true to true, false to true),
            diagram.transitions.map { it.startBreakpoint to it.endBreakpoint },
        )
        assertEquals(listOf("public/pedestrian", "public/crossed", ""), diagram.transitions.map { it.label })
    }

    @Test
    fun `a history is set, changed and taken away, and the state stays the initial one`() {
        val shallow = read(applied(assertNotNull(StatechartEdits.setHistory(crossing, diagram, "operating", StateHistory.SHALLOW))))
        assertEquals(StateHistory.SHALLOW to true, shallow.state("operating")?.let { it.history to it.isInitial })

        val none = applied(assertNotNull(StatechartEdits.setHistory(crossing, diagram, "operating", StateHistory.NONE)))
        assertTrue("INITIAL STATE \"operating\" {" in none, none)
        assertEquals(StateHistory.NONE, read(none).state("operating")?.history)

        val fresh = applied(assertNotNull(StatechartEdits.setHistory(crossing, diagram, "plain", StateHistory.DEEP)))
        assertTrue("DEEP HISTORY STATE \"plain\" { }" in fresh, fresh)
        assertNull(StatechartEdits.setHistory(crossing, diagram, "plain", StateHistory.NONE), "there is nothing to take away")
    }

    @Test
    fun `the breakpoints of a state are set in its block, which comes first and goes with its last word`() {
        val both = read(applied(assertNotNull(StatechartEdits.setStateBreakpoints(crossing, diagram, "operating", entry = true, exit = true))))
        assertEquals(true to true, both.state("operating")?.let { it.entryBreakpoint to it.exitBreakpoint })

        val gone = applied(assertNotNull(StatechartEdits.setStateBreakpoints(crossing, diagram, "operating", entry = false, exit = false)))
        assertNull(read(gone).state("operating")?.breakpointRange)
        assertTrue("// the cars\n            STATE \"cars go\"" in gone, gone)

        val added = applied(assertNotNull(StatechartEdits.setStateBreakpoints(crossing, diagram, "cars go", entry = true, exit = false)))
        val go = assertNotNull(read(added).state("cars go"))
        assertEquals(listOf(true, false, 5L), listOf(go.entryBreakpoint, go.exitBreakpoint, go.delay))
        assertTrue(go.breakpointRange!!.start < go.tickLimitRange!!.start)

        val empty = read(applied(assertNotNull(StatechartEdits.setStateBreakpoints(crossing, diagram, "plain", entry = false, exit = true))))
        assertEquals(true, empty.state("plain")?.exitBreakpoint)
        assertNull(StatechartEdits.setStateBreakpoints(crossing, diagram, "plain", entry = false, exit = false))
    }

    @Test
    fun `the breakpoints of a transition are set before its condition`() {
        val first = diagram.transitions[0].id
        val added = applied(assertNotNull(StatechartEdits.setTransitionBreakpoints(crossing, diagram, first, start = true, end = false)))
        val after = read(added).transitions[0]
        assertEquals(listOf(true, false, "public/pedestrian"), listOf(after.startBreakpoint, after.endBreakpoint, after.label))

        val second = diagram.transitions[1].id
        val gone = read(applied(assertNotNull(StatechartEdits.setTransitionBreakpoints(crossing, diagram, second, start = false, end = false)))).transitions[1]
        assertEquals(listOf(false, false, true, "public/crossed"), listOf(gone.startBreakpoint, gone.endBreakpoint, gone.notLooping, gone.label))

        val third = diagram.transitions[2].id
        val emptied = applied(assertNotNull(StatechartEdits.setTransitionBreakpoints(crossing, diagram, third, start = false, end = false)))
        assertNull(read(emptied).transitions[2].breakpointRange)
    }

    @Test
    fun `a tick limit is added after the breakpoints, edited in its cell and taken out when its code is deleted`() {
        val added = read(applied(assertNotNull(StatechartEdits.addTickLimit(crossing, diagram, "operating"))))
        val operating = assertNotNull(added.state("operating"))
        assertEquals(1L, operating.delay)
        assertTrue(operating.breakpointRange!!.end <= operating.tickLimitRange!!.start)
        assertNull(StatechartEdits.addTickLimit(crossing, diagram, "cars go"), "the state has one already")

        val cell = cells().single { it.key == "cars go.${StatechartMetrics.LIMIT_WORD}" }
        assertEquals("DELAY: 5 TICKS", cell.code.trim())
        val edited = read(applied(assertNotNull(cell.save("DELAY: 2 TICKS\nTIMEOUT: AFTER 7 TICKS TRANSITION TO \"cars wait\""))))
        assertEquals(listOf(2L, 7L, "cars wait"), edited.state("cars go")?.let { listOf(it.delay, it.timeout?.ticks, it.timeout?.target) })
        val emptied = read(applied(assertNotNull(cell.save("  "))))
        assertEquals(null to true, emptied.state("cars go")?.let { it.tickLimitRange to it.hasEntry })
    }

    @Test
    fun `entry and do blocks go behind the breakpoints and the tick limit, and making a state initial keeps histories`() {
        val entry = read(applied(assertNotNull(StatechartEdits.addBlock(crossing, diagram, "cars wait", StatechartEdits.ENTRY))))
        val wait = assertNotNull(entry.state("cars wait"))
        assertTrue(wait.tickLimitRange!!.end <= wait.entryRange!!.start)
        assertEquals(10L to "cars go", wait.timeout?.let { it.ticks to it.target })

        val both = read(applied(assertNotNull(StatechartEdits.addBlock(crossing, diagram, "cars wait", StatechartEdits.DO))))
        assertTrue(both.state("cars wait")!!.tickLimitRange!!.end <= both.state("cars wait")!!.doRange!!.start)

        val initial = read(applied(assertNotNull(StatechartEdits.makeInitial(crossing, diagram, "maintenance"))))
        assertEquals(listOf(false to StateHistory.DEEP, true to StateHistory.SHALLOW), initial.states.take(2).map { it.isInitial to it.history })
    }

    /** The code cells of the crossing, with every state in a box of its own. */
    private fun cells(): List<DiagramCell> {
        val boxes = diagram.allStates().mapIndexed { index, state -> state.name to DiagramBox(0f, 100f * index, 200f, 80f) }.toMap()
        return StatechartCells.of(crossing, diagram, StatechartLayout(boxes, emptyMap(), emptyMap(), emptyList(), 400f, 800f, StatechartMetrics.HEADER))
    }

    /** The crossing with the edits of [change] applied. */
    private fun applied(change: DiagramChange): String =
        change.edits.sortedByDescending { it.range.start }.fold(crossing) { now, edit -> now.replaceRange(edit.range.start, edit.range.end, edit.newText) }

    /** The diagram of [source], which must read without syntax errors. */
    private fun read(source: String): StatechartDiagram = LampStateMachine.diagramOf(source)
}
