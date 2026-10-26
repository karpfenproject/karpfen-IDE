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
package io.karpfen.ide.ui.editor.view

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.state.HoverController
import io.karpfen.ide.ui.editor.state.ContextMenuRequest
import io.karpfen.ide.ui.editor.state.FakeLanguageServices
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Headless mouse tests of `CodeEditor`: click positioning (also behind tabs), drag selection, double click,
 * Shift+click, Cmd+click navigation, the hover after 600 ms with diagnostics, the context menu and wheel scrolling.
 * Positions come from the overlay host's geometry, in text-area coordinates.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorMouseTest {

    private val text = "alpha beta\n\tgamma delta\nepsilon\n"

    @Test
    fun `a click places the caret at the clicked character`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose(text)
        val gamma = text.indexOf("gamma")

        ui.textArea.performMouseInput { click(ui.centerOf(gamma + 2) - Offset(ui.host.boundsOf(gamma)!!.width / 4, 0f)) }
        waitForIdle()

        assertEquals(gamma + 2, ui.state.caret.primary.position, "left half of a character: before it, behind a tab")
        assertTrue(ui.state.isFocused.value, "a click focuses the editor")
    }

    @Test
    fun `dragging selects, double click selects a word, Shift+click extends`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose(text)
        val beta = text.indexOf("beta")
        val delta = text.indexOf("delta")
        val left = { offset: Int -> ui.host.boundsOf(offset)!!.let { Offset(it.left + 1f, it.center.y) } }

        ui.textArea.performMouseInput {
            moveTo(left(1))
            press()
            moveTo(left(beta + 2))
            release()
        }
        waitForIdle()
        assertEquals(TextRange(1, beta + 2), ui.state.caret.primary.selection)

        mainClock.advanceTimeBy(1000)
        ui.textArea.performMouseInput { doubleClick(ui.centerOf(delta + 1)) }
        waitForIdle()
        assertEquals(TextRange(delta, delta + "delta".length), ui.state.caret.primary.selection)

        mainClock.advanceTimeBy(1000)
        ui.textArea.performMouseInput { click(left(1)) }
        ui.editor.performKeyInput { keyDown(Key.ShiftLeft) }
        ui.textArea.performMouseInput { click(left(beta)) }
        ui.editor.performKeyInput { keyUp(Key.ShiftLeft) }
        waitForIdle()
        assertEquals(TextRange(1, beta), ui.state.caret.primary.selection)
    }

    @Test
    fun `Cmd+click navigates to the definition through onNavigate`() = runComposeUiTest {
        val target = Location(Path.of("target.kmeta").toAbsolutePath(), TextRange(0, 1))
        val ui = EditorUi(this, FakeLanguageServices(definitions = { _, _ -> listOf(target) }))
        ui.compose(text)
        mainClock.advanceTimeBy(1000)

        ui.editor.performKeyInput { keyDown(Key.MetaLeft) }
        ui.textArea.performMouseInput { click(ui.centerOf(text.indexOf("beta"))) }
        ui.editor.performKeyInput { keyUp(Key.MetaLeft) }
        waitForIdle()

        assertEquals(listOf(target), ui.navigated)
        assertEquals(0, ui.state.caret.primary.position, "the caret stays")
    }

    @Test
    fun `resting over a diagnostic shows it after 600 ms`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose(text)
        val beta = text.indexOf("beta")
        ui.state.session.setExternalDiagnostics(
            "test",
            ui.text.snapshot.value,
            listOf(Diagnostic(TextRange(beta, beta + 4), Severity.WARNING, "unused", DiagnosticCodes.INTERNAL_ERROR, DiagnosticSource.IDE)),
        )
        waitForIdle()
        mainClock.autoAdvance = false

        ui.textArea.performMouseInput { moveTo(ui.centerOf(beta + 1)) }
        mainClock.advanceTimeBy(CodeEditorState.HOVER_DELAY.inWholeMilliseconds - 100)
        assertNull(ui.state.overlays.hover.value)
        mainClock.advanceTimeBy(200)

        assertEquals(listOf("unused"), assertNotNull(ui.state.overlays.hover.value).diagnostics.map { it.message })
        ui.textArea.performMouseInput { moveTo(Offset(ui.centerOf(beta + 1).x, 10_000f)) }
        mainClock.advanceTimeBy(HoverController.HIDE_GRACE.inWholeMilliseconds + 50)
        assertNull(ui.state.overlays.hover.value, "the pointer left the text")
    }

    @Test
    fun `a right-click opens the context menu at the click in host coordinates`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose(text)
        val offset = text.indexOf("epsilon") + 2
        val position = ui.host.boundsOf(offset)!!.let { Offset(it.left + 1f, it.center.y) }

        ui.textArea.performMouseInput { rightClick(position) }
        waitForIdle()

        assertEquals(ContextMenuRequest(offset, position.x, position.y), ui.state.overlays.contextMenu.value)
        assertEquals(offset, ui.state.caret.primary.position, "the caret moved to the click")
    }

    @Test
    fun `the mouse wheel scrolls the text`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose((0 until 200).joinToString("\n") { "line $it" })

        ui.textArea.performMouseInput { scroll(2f, ScrollWheel.Vertical) }
        waitForIdle()

        val viewport = ui.state.viewport.state.value
        assertEquals(2 * 3 * viewport.lineHeight, viewport.scrollY, 0.5f)
    }
}
