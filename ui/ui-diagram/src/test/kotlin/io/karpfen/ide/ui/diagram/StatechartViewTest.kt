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
import io.karpfen.ide.notation.sync.StatechartState
import io.karpfen.ide.notation.sync.StatechartTracker
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests the statechart view headless: what it shows for a diagram, for none and for a stale one, and its zoom. */
@OptIn(ExperimentalTestApi::class)
class StatechartViewTest {

    @Test
    fun `a diagram is drawn on the canvas with its zoom buttons`() = runComposeUiTest {
        show(stateOf(MACHINE))
        awaitDrawing()

        onNodeWithTag(StatechartViewTestTags.CANVAS).assertIsDisplayed()
        onNodeWithTag(StatechartViewTestTags.ZOOM).assertTextEquals("100%")
        onNodeWithTag(StatechartViewTestTags.STALE).assertDoesNotExist()
        onNodeWithTag(StatechartViewTestTags.EMPTY).assertDoesNotExist()

        onNodeWithTag(StatechartViewTestTags.ZOOM_IN).performClick()
        waitForIdle()
        onNodeWithTag(StatechartViewTestTags.ZOOM).assertTextEquals("110%")

        onNodeWithTag(StatechartViewTestTags.ZOOM).performClick()
        waitForIdle()
        onNodeWithTag(StatechartViewTestTags.ZOOM).assertTextEquals("100%")
    }

    @Test
    fun `without a state machine the view says so`() = runComposeUiTest {
        show(StatechartState.EMPTY)

        onNodeWithTag(StatechartViewTestTags.EMPTY).assertIsDisplayed()
        onNodeWithTag(StatechartViewTestTags.CANVAS).assertDoesNotExist()
    }

    @Test
    fun `a stale diagram is still drawn and marked`() = runComposeUiTest {
        show(stateOf(MACHINE).copy(isStale = true))

        onNodeWithTag(StatechartViewTestTags.CANVAS).assertIsDisplayed()
        onNodeWithTag(StatechartViewTestTags.STALE).assertIsDisplayed()
    }

    @Test
    fun `a click on a state reports the place of its name in the text`() = runComposeUiTest {
        val selected = ArrayList<TextRange>()
        show(stateOf(SINGLE)) { selected += it }
        awaitDrawing()

        onNodeWithTag(StatechartViewTestTags.CANVAS).performTouchInput { click(center) }
        waitForIdle()

        val name = SINGLE.indexOf("alone")
        assertEquals(listOf(TextRange(name, name + "alone".length)), selected, "the only state sits in the middle of the fitted drawing")
    }

    /** Waits until the layout, which is computed off the UI thread, is there. The zoom buttons appear with it. */
    private fun ComposeUiTest.awaitDrawing() {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(StatechartViewTestTags.ZOOM).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
    }

    private fun ComposeUiTest.show(state: StatechartState, onSelect: (TextRange) -> Unit = {}) {
        setContent { KarpfenTheme(darkTheme = false) { StatechartView(state, onSelect = onSelect) } }
        waitForIdle()
    }

    private fun stateOf(text: String): StatechartState {
        val snapshot = TextSnapshot(text, 0)
        return StatechartTracker().update(snapshot, defaultLanguageRegistry().support(KarpfenLanguage.KSTATES).parser.parse(snapshot))
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
