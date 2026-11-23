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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.state.FakeLanguageServices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The completion preview in a composed `CodeEditor`. A template of three lines over `ST` adds two rows below the first
 * line. The lines below move down while the preview shows and move back when it ends, the scroll range covers the added
 * rows, the list is anchored below the preview, and a click lands on the text where it is drawn. A click that shakes
 * a little and a double click stay on the text the user pointed at, although the press moves the lines back.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorCompletionPreviewTest {

    private val text = "ST\nnext line\nlast"

    /** Language services that offer a template of three lines over the word before the offset. */
    private fun template() = FakeLanguageServices(
        completions = { _, offset ->
            listOf(CompletionItem("STATE", CompletionKind.SNIPPET, "STATE {\n    DO\n}", TextRange(0, offset), null, null, null, "s"))
        },
    )

    @Test
    fun `the lines below move down while the preview shows and the scroll range covers them`() = runComposeUiTest {
        val ui = EditorUi(this, template())
        ui.compose(text)
        val next = text.indexOf("next")
        val before = assertNotNull(ui.host.boundsOf(next))
        val contentBefore = ui.state.viewport.state.value.contentHeight
        val rowHeight = before.height
        ui.state.caret.moveTo(2)

        ui.state.execute(EditorActionIds.COMPLETION)
        waitForIdle()

        assertEquals(listOf("STATE {", "    DO", "}"), assertNotNull(ui.state.completion.preview.value).rows)
        assertEquals(before.top + 2 * rowHeight, assertNotNull(ui.host.boundsOf(next)).top, 0.5f)
        assertEquals(contentBefore + 2 * rowHeight, ui.state.viewport.state.value.contentHeight, 0.5f)
        assertEquals(3 * rowHeight, assertNotNull(ui.host.caretLineBounds).bottom, 0.5f, "the list stands below the last row of the preview")
        assertEquals(rowHeight, assertNotNull(ui.host.caretBounds).bottom, 0.5f, "the caret stays one row tall")
        assertEquals(0f, ui.state.viewport.state.value.scrollY, "nothing scrolled")
        assertEquals(text, ui.plain, "the text did not change")

        ui.state.completion.close()
        waitForIdle()

        assertNull(ui.state.completion.preview.value)
        assertEquals(before, ui.host.boundsOf(next), "the lines moved back")
        assertEquals(contentBefore, ui.state.viewport.state.value.contentHeight, 0.5f)
    }

    @Test
    fun `a click while the preview shows lands on the text where it is drawn and ends the preview`() = runComposeUiTest {
        val ui = EditorUi(this, template())
        ui.compose(text)
        val target = text.indexOf("line")
        val unshifted = assertNotNull(ui.host.boundsOf(target))
        ui.state.caret.moveTo(2)
        ui.state.execute(EditorActionIds.COMPLETION)
        waitForIdle()
        val drawn = assertNotNull(ui.host.boundsOf(target))
        assertEquals(unshifted.top + 2 * unshifted.height, drawn.top, 0.5f)

        ui.textArea.performMouseInput { click(drawn.centerLeft.copy(x = drawn.left + 1f)) }
        waitForIdle()

        assertEquals(target, ui.state.caret.primary.position, "the caret went to the line drawn under the pointer")
        assertNull(ui.state.completion.state.value)
        assertNull(ui.state.completion.preview.value)
        assertEquals(text, ui.plain)
    }

    @Test
    fun `a click that shakes by a pixel while the preview shows places the caret and selects nothing`() = runComposeUiTest {
        val ui = EditorUi(this, template())
        ui.compose(text)
        val target = text.indexOf("line")
        ui.state.caret.moveTo(2)
        ui.state.execute(EditorActionIds.COMPLETION)
        waitForIdle()
        val drawn = assertNotNull(ui.host.boundsOf(target))
        val at = Offset(drawn.left + 1f, drawn.center.y)

        ui.textArea.performMouseInput {
            moveTo(at)
            press()
        }
        waitForIdle()
        assertNull(ui.state.completion.preview.value, "the press ended the preview and the lines moved back")
        ui.textArea.performMouseInput {
            moveBy(Offset(1f, 0f))
            release()
        }
        waitForIdle()

        assertEquals(TextRange.empty(target), ui.state.caret.primary.selection, "the lines that moved under the pointer are not selected")
    }

    @Test
    fun `a double click while the preview shows selects the word that was drawn under the pointer`() = runComposeUiTest {
        val ui = EditorUi(this, template())
        ui.compose(text)
        val target = text.indexOf("line")
        ui.state.caret.moveTo(2)
        ui.state.execute(EditorActionIds.COMPLETION)
        waitForIdle()
        val drawn = assertNotNull(ui.host.boundsOf(target))
        val at = Offset(drawn.left + 1f, drawn.center.y)

        ui.textArea.performMouseInput { click(at) }
        waitForIdle()
        assertNull(ui.state.completion.preview.value)
        ui.textArea.performMouseInput { click(at) }
        waitForIdle()

        assertEquals(TextRange(target, target + "line".length), ui.state.caret.primary.selection)
    }
}
