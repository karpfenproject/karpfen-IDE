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
import io.karpfen.ide.notation.diagram.layout.StatechartMetrics
import io.karpfen.ide.notation.diagram.layout.TextMeasurer
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The context menu of a statechart offers the entries of the pages of a state machine where they make sense: the page
 * of a container state and the name of its fragment, final states in a fragment, and `DONE` on what leaves a container
 * state. What makes no sense on a container state or a final state is not offered.
 */
class PageActionsTest {

    private val all = KstatesFeature.entries.toSet()

    private val pagedText = LampStateMachine.PAGED
    private val paged = LampStateMachine.diagramOf(pagedText)
    private val pagedLayout = StatechartLayouter().layout(paged, TextMeasurer.estimating())

    private val fragmentText = LampStateMachine.BULB_TEST
    private val fragment = LampStateMachine.diagramOf(fragmentText)
    private val fragmentLayout = StatechartLayouter().layout(fragment, TextMeasurer.estimating())

    private val actions = StatechartActions(all)

    /** The entries at [point] of [diagram] drawn as [layout] from [text]. */
    private fun at(text: String, diagram: StatechartDiagram, layout: StatechartLayout, point: DiagramPoint): List<DiagramAction> =
        actions.at(text, diagram, layout, point)

    /** A point inside the state [name] of [layout] that lies on no line. */
    private fun inState(layout: StatechartLayout, name: String): DiagramPoint = layout.states.getValue(name).let { DiagramPoint(it.x + 2f, it.y + 2f) }

    /** A point on the line of the transition [id] of [layout]. */
    private fun onTransition(layout: StatechartLayout, id: String): DiagramPoint = layout.routes.first { it.edgeId == id }.points.first()

    /** The middle of the row of the container state [name] of [diagram], drawn as [layout], that names its fragment. */
    private fun onPageRow(diagram: StatechartDiagram, layout: StatechartLayout, name: String): DiagramPoint =
        assertNotNull(StatechartMetrics.pageRow(diagram.state(name)!!, layout.states.getValue(name))).center

    private val canvas = DiagramPoint(-500f, -500f)

    @Test
    fun `a container state opens its page and offers only what a container state can have`() {
        val entries = at(pagedText, paged, pagedLayout, inState(pagedLayout, "self test"))
        val labels = entries.map { it.label }

        assertEquals("Open page bulb test", labels.first())
        assertEquals("bulb test", assertIs<DiagramAction.Follow>(entries.first()).name)
        assertEquals("bulb test", assertIs<DiagramAction.Ask>(entries.first { it.label == "Set fragment" }).initial)
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
    fun `a container state is added on the canvas and inside a state with one click and without a fragment`() {
        val onCanvas = assertIs<DiagramAction.Change>(at(pagedText, paged, pagedLayout, canvas).first { it.label == "Add container state" })
        val inside = assertIs<DiagramAction.Change>(at(pagedText, paged, pagedLayout, inState(pagedLayout, "off")).first { it.label == "Add inner container state" })
        val make = assertIs<DiagramAction.Change>(at(pagedText, paged, pagedLayout, inState(pagedLayout, "off")).first { it.label == "Make container state" })

        val added = onCanvas.change()!!.edits.single().newText
        assertTrue("CONTAINER STATE \"container\" { }" in added, added)
        assertTrue("CONTAINER STATE \"container\" { }" in inside.change()!!.edits.single().newText)
        assertEquals(listOf("CONTAINER "), make.change()!!.edits.map { it.newText })
        assertTrue(at(pagedText, paged, pagedLayout, canvas).none { it.label == "Add final state" })
    }

    @Test
    fun `a container state without a fragment has no page and takes any name typed in, also on its row`() {
        val text = pagedText.replace("CONTAINER STATE \"self test\" USE \"bulb test\"", "CONTAINER STATE \"self test\"")
        val diagram = LampStateMachine.diagramOf(text)
        val layout = StatechartLayouter().layout(diagram, TextMeasurer.estimating())
        val entries = at(text, diagram, layout, onPageRow(diagram, layout, "self test"))

        assertEquals("", diagram.state("self test")?.fragment)
        assertTrue(entries.none { it is DiagramAction.Follow }, "${entries.map { it.label }}")
        assertNull(actions.onDoubleClick(text, diagram, layout, inState(layout, "self test")))
        val set = assertIs<DiagramAction.Ask>(entries.first { it.label == "Set fragment" })
        assertEquals("", set.initial)
        val change = assertIs<DiagramAction.Change>(set.next("lamp \"test\"")).change()
        assertEquals(listOf(" USE \"lamp 'test'\""), change!!.edits.map { it.newText })
    }

    @Test
    fun `an empty name takes the fragment out again`() {
        val set = assertIs<DiagramAction.Ask>(at(pagedText, paged, pagedLayout, onPageRow(paged, pagedLayout, "self test")).first { it.label == "Set fragment" })

        val change = assertIs<DiagramAction.Change>(set.next("  ")).change()
        assertEquals(listOf(""), change!!.edits.map { it.newText })
    }

    @Test
    fun `a state without actions and inner states becomes a container state, and a container state a plain one`() {
        fun labels(text: String, name: String): List<String> {
            val diagram = LampStateMachine.diagramOf(text)
            val layout = StatechartLayouter().layout(diagram, TextMeasurer.estimating())
            return at(text, diagram, layout, inState(layout, name)).map { it.label }
        }
        val acting = pagedText.replace("INITIAL STATE \"off\" { }", "INITIAL STATE \"off\" { DO { } }")

        assertTrue("Make container state" in labels(pagedText, "off"))
        assertTrue("Make container state" !in labels(acting, "off"), "it has actions")
        assertTrue("Make container state" !in labels(pagedText, "on"), "it has inner states")
        assertTrue(listOf("Make container state", "Make plain state").none { it in labels(fragmentText, "passed") }, "a final state")
        assertTrue("Make plain state" in labels(pagedText, "self test") && "Make container state" !in labels(pagedText, "self test"))
        assertTrue("Make plain state" !in labels(pagedText, "off"))
    }

    @Test
    fun `a fragment adds final states and container states`() {
        val labels = at(fragmentText, fragment, fragmentLayout, canvas).map { it.label }

        assertTrue("Add final state" in labels && "Add container state" in labels, "$labels")
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
