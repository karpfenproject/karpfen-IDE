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

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import io.karpfen.ide.ui.editor.state.EditorViewport
import io.karpfen.ide.ui.editor.state.FakeLanguageServices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scrolling in the composed editor: the caret stays visible with a 3-line margin when keys and typing move it,
 * `scrollTo(center)` centres, and requests made before the first composition (scroll and focus) take effect once the
 * editor is composed.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorScrollTest {

    private val lines = (0 until 300).joinToString("\n") { "line number $it of the document" }

    private fun EditorUi.caretLine(): Int = text.snapshot.value.lineIndex.lineOf(state.caret.primary.position)

    /** True if the caret's line is completely visible with [margin] lines of context where the text allows it. */
    private fun EditorUi.caretVisible(margin: Int = 0): Boolean {
        val viewport = state.viewport.state.value
        val top = caretLine() * viewport.lineHeight
        val above = minOf(margin, caretLine()) * viewport.lineHeight
        val bottom = top + (margin + 1) * viewport.lineHeight
        return top - above >= viewport.scrollY - 0.5f && bottom <= viewport.scrollY + viewport.viewportHeight + 0.5f
    }

    @Test
    fun `arrow keys at the bottom edge scroll the caret into view with its margin`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose(lines)
        ui.editor.requestFocus()
        val visibleLines = (ui.state.viewport.state.value.let { it.viewportHeight / it.lineHeight }).toInt()

        ui.editor.performKeyInput { repeat(visibleLines + 5) { pressKey(Key.DirectionDown) } }
        waitForIdle()

        assertEquals(visibleLines + 5, ui.caretLine())
        assertTrue(ui.state.viewport.state.value.scrollY > 0f)
        assertTrue(ui.caretVisible(margin = EditorViewport.CARET_MARGIN_LINES), "${ui.state.viewport.state.value}")
        assertNull(ui.state.scrollRequest.value, "the view consumed the request")
    }

    @Test
    fun `jumping to the document end and typing there keep the caret visible`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose(lines)
        ui.editor.requestFocus()

        ui.editor.performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.DirectionDown) } }
        waitForIdle()
        assertEquals(299, ui.caretLine())
        assertTrue(ui.caretVisible())

        ui.editor.performKeyInput { pressKey(Key.Enter) }
        ui.editor.performTextInput("more")
        waitForIdle()
        assertEquals(300, ui.caretLine())
        assertTrue(ui.caretVisible(margin = EditorViewport.CARET_MARGIN_LINES), "${ui.state.viewport.state.value}")
    }

    @Test
    fun `typing beyond the right edge scrolls horizontally`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose("x")
        ui.editor.requestFocus()

        ui.editor.performTextInput("y".repeat(200))
        waitForIdle()

        val viewport = ui.state.viewport.state.value
        assertTrue(viewport.scrollX > 0f)
        val caret = assertNotNull(ui.host.caretBounds, "the caret is visible")
        assertTrue(caret.right <= viewport.viewportWidth)
    }

    @Test
    fun `scrollTo centres the offset`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose(lines)
        val offset = ui.text.snapshot.value.lineIndex.lineStart(150)

        ui.state.scrollTo(offset, center = true)
        waitForIdle()

        val viewport = ui.state.viewport.state.value
        assertEquals(150.5f * viewport.lineHeight - viewport.viewportHeight / 2f, viewport.scrollY, 1f)
    }

    @Test
    fun `scroll and focus requests made before the first composition take effect once composed`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())

        ui.compose(lines) { state ->
            state.caret.moveTo(state.snapshot.value.lineIndex.lineStart(200))
            state.scrollTo(state.caret.primary.position, center = true)
            state.requestFocus()
        }
        waitForIdle()

        assertTrue(ui.caretVisible(margin = EditorViewport.CARET_MARGIN_LINES), "${ui.state.viewport.state.value}")
        assertTrue(ui.state.viewport.state.value.scrollY > 0f)
        assertTrue(ui.state.isFocused.value)
        assertEquals(false, ui.state.focusRequested.value, "consumed")
        assertNull(ui.state.scrollRequest.value, "consumed")
    }
}
