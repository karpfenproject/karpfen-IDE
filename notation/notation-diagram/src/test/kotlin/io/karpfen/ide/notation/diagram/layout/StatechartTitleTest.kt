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
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the title line of a statechart: what it says for an attached and an attachable state machine, and that both
 * layouts place it above every other element, so it covers no state and follows the layout by hand.
 */
class StatechartTitleTest {

    private val text = TextMeasurer.estimating()
    private val lamp = LampStateMachine.diagramOf(LampStateMachine.LAMP)
    private val glowing = LampStateMachine.diagramOf(
        LampStateMachine.LAMP.replace("STATEMACHINE ATTACHED TO \"Lamp\"", "STATEMACHINE \"glowing\" ATTACHABLE TO \"Lamp\", \"Bulb\""),
    )

    @Test
    fun `an attached state machine is titled with ATTACHED TO and its type`() {
        assertEquals("ATTACHED TO Lamp", lamp.title)
    }

    @Test
    fun `an attachable state machine is titled with its name, ATTACHABLE TO and its types`() {
        assertEquals("glowing ATTACHABLE TO Lamp, Bulb", glowing.title)
    }

    @Test
    fun `an attached state machine whose text names no type has no title`() {
        assertNull(lamp.copy(types = emptyList()).title)
    }

    @Test
    fun `the automatic layout writes the title above the drawing, as wide as its text`() {
        val layout = StatechartArrangement.automatic(glowing, text)

        assertAbove(layout, glowing)
    }

    @Test
    fun `the layout by hand writes the title above the topmost element and moves it along`() {
        val vis = VisLayout(boxes = mapOf("off" to DiagramPoint(100f, 100f), "broken" to DiagramPoint(100f, 400f)))
        val layout = StatechartArrangement.byHand(lamp, vis, text)
        val raised = StatechartArrangement.byHand(lamp, vis.copy(boxes = vis.boxes + ("broken" to DiagramPoint(100f, -200f))), text)

        assertAbove(layout, lamp)
        assertAbove(raised, lamp)
        assertTrue(assertNotNull(raised.title).bottom < assertNotNull(layout.title).bottom, "the title rises with the state")
    }

    @Test
    fun `the bounds that a view fits take in the title`() {
        val layout = StatechartArrangement.automatic(lamp, text)
        val title = assertNotNull(layout.title)

        assertEquals(title.y, layout.bounds.y)
        assertTrue(layout.bounds.x <= title.x && layout.bounds.right >= title.right)
    }

    @Test
    fun `a state machine without a title has no title line`() {
        val untitled = lamp.copy(types = emptyList())

        assertNull(StatechartArrangement.automatic(untitled, text).title)
        assertNull(StatechartArrangement.byHand(untitled, VisLayout(), text).title)
    }

    /**
     * Asserts that [layout] writes the title of [diagram] in the middle of the width of the drawing, as wide as its
     * text, with the gap of the metrics between the title and the topmost element below it.
     */
    private fun assertAbove(layout: StatechartLayout, diagram: StatechartDiagram) {
        val title = assertNotNull(layout.title)
        val drawing = layout.copy(title = null).bounds

        assertEquals(drawing.center.x, title.center.x)
        assertEquals(drawing.y - StatechartMetrics.TITLE_GAP, title.bottom)
        assertEquals(text.widthOf(checkNotNull(diagram.title)), title.width)
        assertEquals(StatechartMetrics.TITLE_HEIGHT, title.height)
    }
}
