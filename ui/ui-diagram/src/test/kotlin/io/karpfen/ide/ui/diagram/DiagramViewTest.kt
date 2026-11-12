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
package io.karpfen.ide.ui.diagram

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.notation.diagram.edit.DiagramChange
import io.karpfen.ide.notation.diagram.layout.DiagramLayout
import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.notation.sync.DiagramState
import io.karpfen.ide.notation.sync.DiagramTracker
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the diagram view headless: what it shows for a diagram, for none and for a stale one, its zoom, and that it
 * draws every kind of diagram.
 */
@OptIn(ExperimentalTestApi::class)
class DiagramViewTest {

    @Test
    fun `a diagram is drawn on the canvas with its zoom buttons`() = runComposeUiTest {
        show(DiagramNotation.STATECHART, stateOf(DiagramNotation.STATECHART, KarpfenLanguage.KSTATES, MACHINE))
        awaitDrawing()

        onNodeWithTag(DiagramViewTestTags.CANVAS).assertIsDisplayed()
        onNodeWithTag(DiagramViewTestTags.ZOOM).assertTextEquals("100%")
        onNodeWithTag(DiagramViewTestTags.STALE).assertDoesNotExist()
        onNodeWithTag(DiagramViewTestTags.EMPTY).assertDoesNotExist()

        onNodeWithTag(DiagramViewTestTags.ZOOM_IN).performClick()
        waitForIdle()
        onNodeWithTag(DiagramViewTestTags.ZOOM).assertTextEquals("110%")

        onNodeWithTag(DiagramViewTestTags.ZOOM).performClick()
        waitForIdle()
        onNodeWithTag(DiagramViewTestTags.ZOOM).assertTextEquals("100%")
    }

    @Test
    fun `without a state machine the view says so`() = runComposeUiTest {
        show(DiagramNotation.STATECHART, DiagramState.EMPTY)

        onNodeWithTag(DiagramViewTestTags.EMPTY).assertIsDisplayed()
        onNodeWithTag(DiagramViewTestTags.CANVAS).assertDoesNotExist()
    }

    @Test
    fun `a stale diagram is still drawn and marked`() = runComposeUiTest {
        show(DiagramNotation.STATECHART, stateOf(DiagramNotation.STATECHART, KarpfenLanguage.KSTATES, MACHINE).copy(isStale = true))

        onNodeWithTag(DiagramViewTestTags.CANVAS).assertIsDisplayed()
        onNodeWithTag(DiagramViewTestTags.STALE).assertIsDisplayed()
    }

    @Test
    fun `a click on a state reports the place of its name in the text`() = runComposeUiTest {
        val selected = ArrayList<TextRange>()
        show(DiagramNotation.STATECHART, stateOf(DiagramNotation.STATECHART, KarpfenLanguage.KSTATES, SINGLE)) { selected += it }
        awaitDrawing()

        onNodeWithTag(DiagramViewTestTags.CANVAS).performTouchInput { click(center) }
        waitForIdle()

        val name = SINGLE.indexOf("alone")
        assertEquals(listOf(TextRange(name, name + "alone".length)), selected, "the only state sits in the middle of the fitted drawing")
    }

    @Test
    fun `the class diagram of a metamodel is drawn, and a click on a type reports its name`() = runComposeUiTest {
        val selected = ArrayList<TextRange>()
        show(DiagramNotation.TYPES, stateOf(DiagramNotation.TYPES, KarpfenLanguage.KMETA, TYPE)) { selected += it }
        awaitDrawing()

        onNodeWithTag(DiagramViewTestTags.CANVAS).performTouchInput { click(center) }
        waitForIdle()

        val name = TYPE.indexOf("Bulb")
        assertEquals(listOf(TextRange(name, name + "Bulb".length)), selected, "the only type sits in the middle of the fitted drawing")
    }

    @Test
    fun `the object diagram of a model is drawn`() = runComposeUiTest {
        show(DiagramNotation.OBJECTS, stateOf(DiagramNotation.OBJECTS, KarpfenLanguage.KMODEL, OBJECTS))
        awaitDrawing()

        onNodeWithTag(DiagramViewTestTags.CANVAS).assertIsDisplayed()
        onNodeWithTag(DiagramViewTestTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun `a metamodel without types says that there is nothing to draw`() = runComposeUiTest {
        show(DiagramNotation.TYPES, stateOf(DiagramNotation.TYPES, KarpfenLanguage.KMETA, "// no types yet"))

        onNodeWithTag(DiagramViewTestTags.EMPTY).assertTextEquals(DiagramNotation.TYPES.nothingToDraw)
        onNodeWithTag(DiagramViewTestTags.CANVAS).assertDoesNotExist()
    }

    @Test
    fun `a right click on a line opens a menu, and only the click on its entry adds the waypoint`() = runComposeUiTest {
        val saved = ArrayList<VisLayout>()
        show(DiagramNotation.OBJECTS, stateOf(DiagramNotation.OBJECTS, KarpfenLanguage.KMODEL, PAIR), onSave = { saved += it })
        awaitDrawing()

        onNodeWithTag(DiagramViewTestTags.CANVAS).performMouseInput { rightClick(center) }
        waitForIdle()

        onNodeWithText(DiagramEditing.ADD_WAYPOINT).assertIsDisplayed()
        assertEquals(emptyList(), saved, "the right click alone changes nothing")

        onNodeWithText(DiagramEditing.ADD_WAYPOINT).performClick()
        waitForIdle()

        onNodeWithText(DiagramEditing.ADD_WAYPOINT).assertDoesNotExist()
        assertEquals(listOf("has:a."), saved.single().waypoints.keys.toList())
    }

    @Test
    fun `a right click on the empty canvas of a statechart offers to add a state, which edits the text`() = runComposeUiTest {
        val changes = ArrayList<DiagramChange>()
        show(DiagramNotation.STATECHART, stateOf(DiagramNotation.STATECHART, KarpfenLanguage.KSTATES, SINGLE), onChange = { changes += it })
        awaitDrawing()

        onNodeWithTag(DiagramViewTestTags.CANVAS).performMouseInput { rightClick(topLeft + Offset(4f, 4f)) }
        waitForIdle()
        onNodeWithText("Add state").performClick()
        waitForIdle()

        val change = changes.single()
        assertTrue(change.arranges, "the new state is placed where the user clicked")
        assertTrue("STATE \"state\" { }" in change.edits.single().newText, change.edits.toString())
    }

    @Test
    fun `an action that needs a state waits for a click on one and can be given up`() = runComposeUiTest {
        val changes = ArrayList<DiagramChange>()
        show(DiagramNotation.STATECHART, stateOf(DiagramNotation.STATECHART, KarpfenLanguage.KSTATES, SINGLE), onChange = { changes += it })
        awaitDrawing()

        onNodeWithTag(DiagramViewTestTags.CANVAS).performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithText("Add transition from here").performClick()
        waitForIdle()
        onNodeWithTag(DiagramViewTestTags.HINT).assertIsDisplayed()

        onNodeWithTag(DiagramViewTestTags.CANVAS).performTouchInput { click(center) }
        waitForIdle()
        onNodeWithTag(DiagramViewTestTags.HINT).assertDoesNotExist()
        assertTrue("TRANSITION \"alone\" -> \"alone\" { }" in changes.single().edits.single().newText)

        onNodeWithTag(DiagramViewTestTags.CANVAS).performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithText("Add transition from here").performClick()
        waitForIdle()
        onNodeWithTag(DiagramViewTestTags.CANVAS).performMouseInput { rightClick(topLeft + Offset(4f, 4f)) }
        waitForIdle()
        onNodeWithTag(DiagramViewTestTags.HINT).assertDoesNotExist()
        assertEquals(1, changes.size, "the second action was given up")
    }

    @Test
    fun `a click on the marker of a code cell opens the code, and Save writes it into the text`() = runComposeUiTest {
        val changes = ArrayList<DiagramChange>()
        show(DiagramNotation.STATECHART, stateOf(DiagramNotation.STATECHART, KarpfenLanguage.KSTATES, WITH_ENTRY), onChange = { changes += it })
        awaitDrawing()

        onNodeWithTag(DiagramViewTestTags.CANVAS).performTouchInput { click(center + Offset(0f, 14f)) }
        waitForIdle()
        onNodeWithTag(DiagramViewTestTags.CELL_CODE).assertTextEquals("SET(\"mode\", \"on\")")

        onNodeWithTag(DiagramViewTestTags.CELL_CODE).performTextReplacement("SET(\"mode\", \"lit\")")
        onNodeWithTag(DiagramViewTestTags.CELL_SAVE).performClick()
        waitForIdle()

        onNodeWithTag(DiagramViewTestTags.CELL).assertDoesNotExist()
        assertEquals(listOf("{ SET(\"mode\", \"lit\") }"), changes.single().edits.map { it.newText })

        onNodeWithTag(DiagramViewTestTags.CANVAS).performTouchInput { click(center + Offset(0f, 14f)) }
        waitForIdle()
        onNodeWithTag(DiagramViewTestTags.CELL_CLOSE).performClick()
        waitForIdle()
        onNodeWithTag(DiagramViewTestTags.CELL).assertDoesNotExist()
        assertEquals(1, changes.size, "closing writes nothing")
    }

    @Test
    fun `renaming a state asks for the name in a field`() = runComposeUiTest {
        val changes = ArrayList<DiagramChange>()
        show(DiagramNotation.STATECHART, stateOf(DiagramNotation.STATECHART, KarpfenLanguage.KSTATES, SINGLE), onChange = { changes += it })
        awaitDrawing()

        onNodeWithTag(DiagramViewTestTags.CANVAS).performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithText("Rename").performClick()
        waitForIdle()
        onNodeWithTag(DiagramViewTestTags.ASK).performTextReplacement("idle")
        onNodeWithTag(DiagramViewTestTags.ASK).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        onNodeWithTag(DiagramViewTestTags.ASK).assertDoesNotExist()
        assertEquals(listOf("idle"), changes.single().edits.map { it.newText })
    }

    @Test
    fun `a right click in a class diagram offers what fits the place`() = runComposeUiTest {
        show(DiagramNotation.TYPES, stateOf(DiagramNotation.TYPES, KarpfenLanguage.KMETA, TYPE))
        awaitDrawing()

        onNodeWithTag(DiagramViewTestTags.CANVAS).performMouseInput { rightClick(topLeft + Offset(4f, 4f)) }
        waitForIdle()
        onNodeWithText("Add type").assertIsDisplayed()
        onNodeWithText("Add waypoint").assertDoesNotExist()
    }

    /** Waits until the layout, which is computed off the UI thread, is there. The zoom buttons appear with it. */
    private fun ComposeUiTest.awaitDrawing() {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(DiagramViewTestTags.ZOOM).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
    }

    private fun <D : Any, L : DiagramLayout> ComposeUiTest.show(
        notation: DiagramNotation<D, L>,
        state: DiagramState<D>,
        onSave: (VisLayout) -> Unit = {},
        onChange: (DiagramChange) -> Unit = {},
        onSelect: (TextRange) -> Unit = {},
    ) {
        val editing = DiagramEditing(notation.arrangement, onSave)
        val change: (DiagramChange) -> Boolean = { made ->
            onChange(made)
            true
        }
        setContent { KarpfenTheme(darkTheme = false) { DiagramView(notation, state, editing, onSelect = onSelect, onChange = change) } }
        waitForIdle()
    }

    private fun <D : Any> stateOf(notation: DiagramNotation<D, *>, language: KarpfenLanguage, text: String): DiagramState<D> {
        val snapshot = TextSnapshot(text, 0)
        return DiagramTracker(notation.projection).update(snapshot, defaultLanguageRegistry().support(language).parser.parse(snapshot))
    }

    private companion object {
        val MACHINE = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    STATE "on" {
                        STATE "full" { }
                    }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "full" { CONDITION { EVENT("switch", "press") } }
                    TRANSITION "on" -> "off" { }
                }
            }
        """.trimIndent()

        val TYPE = """
            type "Bulb" "A light bulb" {
                prop("watts", "number")
            }
        """.trimIndent()

        val OBJECTS = """
            make object "lamp":"Lamp" {
                prop("mode") -> "off"
                has("bulb") -> make object "bulb":"Bulb" { }
            }
        """.trimIndent()

        /**
         * Two objects of the same size, one above the other, joined by a link without a label. So the middle of the
         * fitted drawing lies on the line between them.
         */
        val PAIR = """
            make object "a":"A" {
                has("") -> make object "b":"B" { }
            }
        """.trimIndent()

        /** One state with an entry block. Its marker stands in the middle of the fitted drawing, below the name. */
        val WITH_ENTRY = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    STATE "alone" {
                        ENTRY { SET("mode", "on") }
                    }
                }
                TRANSITIONS { }
            }
        """.trimIndent()

        val SINGLE = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    STATE "alone" { }
                }
                TRANSITIONS { }
            }
        """.trimIndent()
    }
}
