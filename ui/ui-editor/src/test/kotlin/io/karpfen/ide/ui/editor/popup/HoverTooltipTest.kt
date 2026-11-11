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
package io.karpfen.ide.ui.editor.popup

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.state.HoverContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The hover tooltip through the overlay contract. It shows diagnostics with severity icons, or else the language hover.
 * It is placed at the hovered range, and a click dismisses it.
 */
@OptIn(ExperimentalTestApi::class)
class HoverTooltipTest {

    /** A state with an EVAL body. */
    private val text = "state Idle {\n    EVAL { x }\n}"

    /** The range of `Idle` in [text]. */
    private val idle = TextRange(6, 10)

    /** A parser diagnostic on [idle] with [severity] and [message]. */
    private fun diagnostic(severity: Severity, message: String) =
        Diagnostic(idle, severity, message, DiagnosticCodes.SYNTAX_ERROR, DiagnosticSource.PARSER)

    @Test
    fun `nothing is shown without hover content`() = popupTest(text) {
        onNodeWithTag(EditorPopupTags.HOVER).assertDoesNotExist()
    }

    @Test
    fun `diagnostics are shown with their severity icons below the hovered text`() = popupTest(text) { harness ->
        val content = HoverContent(
            idle,
            listOf(diagnostic(Severity.ERROR, "missing '}'"), diagnostic(Severity.WARNING, "unused state"), diagnostic(Severity.INFO, "note")),
            hover = null,
        )

        runOnIdle { harness.model.overlays.showHover(content) }

        onNodeWithText("missing '}'").assertIsDisplayed()
        onNodeWithText("unused state").assertIsDisplayed()
        onNodeWithContentDescription("Error").assertExists()
        onNodeWithContentDescription("Warning").assertExists()
        onNodeWithContentDescription("Information").assertExists()
        val bounds = boundsInTextArea(EditorPopupTags.HOVER)
        assertEquals(6 * TestGeometry.CHAR / density.density, bounds.left.value, 0.5f)
        assertEquals(TestGeometry.LINE / density.density, bounds.top.value, 0.5f)
    }

    @Test
    fun `the language hover is shown when there are no diagnostics`() = popupTest(text) { harness ->
        runOnIdle { harness.model.overlays.showHover(HoverContent(idle, emptyList(), Hover(idle, "state Idle (3 transitions)"))) }

        onNodeWithText("state Idle (3 transitions)").assertIsDisplayed()
    }

    @Test
    fun `diagnostics take precedence over the language hover`() = popupTest(text) { harness ->
        runOnIdle {
            harness.model.overlays.showHover(HoverContent(idle, listOf(diagnostic(Severity.ERROR, "broken")), Hover(idle, "state Idle")))
        }

        onNodeWithText("broken").assertIsDisplayed()
        onNodeWithText("state Idle").assertDoesNotExist()
    }

    @Test
    fun `a click on the tooltip hides it`() = popupTest(text) { harness ->
        runOnIdle { harness.model.overlays.showHover(HoverContent(idle, emptyList(), Hover(idle, "state Idle"))) }

        onNodeWithTag(EditorPopupTags.HOVER).performClick()

        onNodeWithTag(EditorPopupTags.HOVER).assertDoesNotExist()
        runOnIdle {
            assertNull(harness.model.overlays.hover.value)
            assertEquals(0, harness.model.focusRequests, "the text area keeps focus")
        }
    }

    @Test
    fun `the tooltip opens above the hovered text when there is no room below`() = popupTest((1..60).joinToString("\n") { "line $it" }) { harness ->
        val snapshot = harness.model.snapshot.value
        val areaLines = (onNodeWithTag(TEXT_AREA).fetchSemanticsNode().size.height / TestGeometry.LINE).toInt()
        val line = areaLines - 1
        val start = snapshot.offsetOf(line, 0)

        runOnIdle { harness.model.overlays.showHover(HoverContent(TextRange(start, start + 4), emptyList(), Hover(TextRange(start, start + 4), "a\nb\nc"))) }

        val bounds = boundsInTextArea(EditorPopupTags.HOVER)
        assertEquals(line * TestGeometry.LINE / density.density, bounds.bottom.value, 0.5f)
    }

    @Test
    fun `nothing is shown while the hovered range is off screen`() = popupTest(text) { harness ->
        harness.host.offscreenLines = setOf(0)

        runOnIdle { harness.model.overlays.showHover(HoverContent(idle, emptyList(), Hover(idle, "state Idle"))) }

        onNodeWithTag(EditorPopupTags.HOVER).assertDoesNotExist()
    }
}
