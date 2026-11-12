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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.notation.diagram.layout.DiagramLayout
import io.karpfen.ide.notation.sync.DiagramState
import io.karpfen.ide.notation.sync.DiagramTracker
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import kotlin.test.Test
import kotlin.test.assertEquals

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

    /** Waits until the layout, which is computed off the UI thread, is there. The zoom buttons appear with it. */
    private fun ComposeUiTest.awaitDrawing() {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(DiagramViewTestTags.ZOOM).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
    }

    private fun <D : Any, L : DiagramLayout> ComposeUiTest.show(
        notation: DiagramNotation<D, L>,
        state: DiagramState<D>,
        onSelect: (TextRange) -> Unit = {},
    ) {
        val editing = DiagramEditing(notation.arrangement) { }
        setContent { KarpfenTheme(darkTheme = false) { DiagramView(notation, state, editing, onSelect = onSelect) } }
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
