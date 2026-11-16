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
import io.karpfen.ide.notation.diagram.layout.StatechartLayout
import io.karpfen.ide.notation.diagram.layout.StatechartLayouter
import io.karpfen.ide.notation.diagram.layout.TextMeasurer
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The context menu of a statechart offers the entries of the pages of a state machine where they make sense: the page
 * of a container state and the fragments it can use, final states in a fragment, and `DONE` on what leaves a container
 * state. What makes no sense on a container state or a final state is not offered.
 */
class PageActionsTest {

    private val fragments = listOf("bulb test", "switch test", "wiring")
    private val all = KstatesFeature.entries.toSet()

    private val pagedText = LampStateMachine.PAGED
    private val paged = LampStateMachine.diagramOf(pagedText)
    private val pagedLayout = StatechartLayouter().layout(paged, TextMeasurer.estimating())

    private val fragmentText = LampStateMachine.BULB_TEST
    private val fragment = LampStateMachine.diagramOf(fragmentText)
    private val fragmentLayout = StatechartLayouter().layout(fragment, TextMeasurer.estimating())

    private val actions = StatechartActions(all, fragments)

    /** The entries at [point] of [diagram] drawn as [layout] from [text]. */
    private fun at(text: String, diagram: StatechartDiagram, layout: StatechartLayout, point: DiagramPoint): List<DiagramAction> =
        actions.at(text, diagram, layout, point)

    /** A point inside the state [name] of [layout] that lies on no line. */
    private fun inState(layout: StatechartLayout, name: String): DiagramPoint = layout.states.getValue(name).let { DiagramPoint(it.x + 2f, it.y + 2f) }

    /** A point on the line of the transition [id] of [layout]. */
    private fun onTransition(layout: StatechartLayout, id: String): DiagramPoint = layout.routes.first { it.edgeId == id }.points.first()

    private val canvas = DiagramPoint(-500f, -500f)

    @Test
    fun `a container state opens its page and offers only what a container state can have`() {
        val entries = at(pagedText, paged, pagedLayout, inState(pagedLayout, "self test"))
        val labels = entries.map { it.label }

        assertEquals("Open page bulb test", labels.first())
        assertEquals("bulb test", assertIs<DiagramAction.Follow>(entries.first()).name)
        val use = assertIs<DiagramAction.Choose>(entries.first { it.label == "Use another fragment" })
        assertEquals(listOf("switch test", "wiring"), use.options)
        assertTrue(labels.none { it in listOf("Add inner state", "Add inner container state", "Add entry actions", "Add do actions") }, "$labels")
        assertTrue(listOf("Add transition from here", "Make initial state", "Remove history", "Delete state").all { it in labels }, "$labels")
    }

    @Test
    fun `a double click on a container state opens its page, elsewhere it does nothing`() {
        val open = actions.onDoubleClick(pagedText, paged, pagedLayout, inState(pagedLayout, "self test"))

        assertEquals("bulb test", assertIs<DiagramAction.Follow>(open).name)
        assertNull(actions.onDoubleClick(pagedText, paged, pagedLayout, inState(pagedLayout, "off")))
        assertNull(actions.onDoubleClick(pagedText, paged, pagedLayout, canvas))
    }

    @Test
    fun `a container state is added on the canvas and inside a state with a choice among the fragments`() {
        val onCanvas = assertIs<DiagramAction.Choose>(at(pagedText, paged, pagedLayout, canvas).first { it.label == "Add container state" })
        val inside = at(pagedText, paged, pagedLayout, inState(pagedLayout, "off")).first { it.label == "Add inner container state" }

        assertEquals(fragments, onCanvas.options)
        assertEquals(fragments, assertIs<DiagramAction.Choose>(inside).options)
        val change = assertIs<DiagramAction.Change>(onCanvas.next("wiring")).change()
        assertTrue(change!!.edits.single().newText.contains("CONTAINER STATE \"wiring\" USE \"wiring\" { }"))
        assertTrue(at(pagedText, paged, pagedLayout, canvas).none { it.label == "Add final state" })
        assertTrue(StatechartActions(all).at(pagedText, paged, pagedLayout, canvas).none { it.label == "Add container state" }, "no fragments, no entry")
    }

    @Test
    fun `a fragment adds final states and never uses itself`() {
        val labels = at(fragmentText, fragment, fragmentLayout, canvas).map { it.label }
        val add = assertIs<DiagramAction.Choose>(at(fragmentText, fragment, fragmentLayout, canvas).first { it.label == "Add container state" })

        assertTrue("Add final state" in labels, "$labels")
        assertEquals(listOf("switch test", "wiring"), add.options)
    }

    @Test
    fun `a final state is never left, never initial, has no actions, no tick limit and no history, and stays at the top`() {
        val labels = at(fragmentText, fragment, fragmentLayout, inState(fragmentLayout, "failed")).map { it.label }

        assertTrue(
            labels.none {
                it in listOf("Add transition from here", "Make initial state", "Add inner state", "Add entry actions", "Add do actions", "Add tick limit") ||
                    it.startsWith("Remember") || it.startsWith("Make inner state")
            },
            "$labels",
        )
        assertTrue(listOf("Rename", "Add breakpoint at entry", "Delete state").all { it in labels }, "$labels")
        val start = assertIs<DiagramAction.Pick>(at(fragmentText, fragment, fragmentLayout, canvas).first { it.label == "Add transition" })
        assertEquals(setOf("powered"), start.candidates)
    }

    @Test
    fun `no state moves into a container state or a final state`() {
        val move = assertIs<DiagramAction.Pick>(at(pagedText, paged, pagedLayout, inState(pagedLayout, "off")).first { it.label == "Make inner state of" })

        assertEquals(setOf("on"), move.candidates)
    }

    @Test
    fun `DONE is set on a transition that leaves a container state and removed where it stands`() {
        fun labels(index: Int) = at(pagedText, paged, pagedLayout, onTransition(pagedLayout, paged.transitions[index].id)).map { it.label }

        assertTrue("Wait until the fragment is done (DONE)" in labels(2), "${labels(2)}")
        assertTrue("Remove DONE" in labels(1) && "Wait until the fragment is done (DONE)" !in labels(1), "${labels(1)}")
        assertTrue(labels(0).none { "DONE" in it }, "${labels(0)}")
    }
}
