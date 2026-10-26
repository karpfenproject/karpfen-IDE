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
package io.karpfen.ide.ui.editor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.completion.CompletionTrigger
import io.karpfen.ide.ui.editor.popup.EditorPopupTags
import io.karpfen.ide.ui.editor.state.FakeLanguageServices
import io.karpfen.ide.ui.editor.view.EditorUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A completion list that cannot be shown takes no keys, with the real `EditorPopups` over `CodeEditor`: scrolling the
 * caret out of view closes the session, so Enter breaks the line instead of accepting an item nobody sees. A caret that
 * is about to be revealed is not scrolled away: typing and Ctrl+M at an off-screen caret show it with their list.
 */
@OptIn(ExperimentalTestApi::class)
class HiddenCompletionTest {

    @Test
    fun `completion scrolled out of view closes and Enter breaks the line again`() = runComposeUiTest {
        val services = FakeLanguageServices(
            completions = { _, offset -> listOf(CompletionItem("observe", CompletionKind.KEYWORD, "observe", TextRange.empty(offset), null, null, null, "o")) },
        )
        val ui = EditorUi(this, services)
        ui.compose((1..200).joinToString("\n") { "line $it" }, slots = HostRecordingPopups())
        ui.editor.requestFocus()
        runOnIdle {
            ui.state.caret.moveTo(3)
            ui.state.completion.open(CompletionTrigger.EXPLICIT)
        }
        waitForIdle()
        onNodeWithTag(EditorPopupTags.COMPLETION).assertExists()

        runOnIdle { ui.state.viewport.scrollBy(0f, 3_000f) }
        waitForIdle()

        onNodeWithTag(EditorPopupTags.COMPLETION).assertDoesNotExist()
        assertNull(ui.state.completion.state.value, "a session whose list cannot be shown is closed")
        ui.editor.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("lin", ui.text.snapshot.value.lineText(0), "Enter broke the line instead of accepting a hidden item")
    }

    @Test
    fun `typing at a caret scrolled out of view reveals it with the completion the keystroke triggered`() = runComposeUiTest {
        val ui = EditorUi(this, services())
        ui.compose(LINES, slots = HostRecordingPopups())
        ui.editor.requestFocus()
        runOnIdle { ui.state.caret.moveTo(3) }
        runOnIdle { ui.state.viewport.scrollBy(0f, 3_000f) }
        waitForIdle()
        assertTrue(ui.state.viewport.state.value.scrollY > 0f)

        ui.editor.performTextInput("e")
        waitForIdle()

        assertEquals(0f, ui.state.viewport.state.value.scrollY, "the keystroke revealed the caret")
        assertNotNull(ui.state.completion.state.value, "the completion the keystroke triggered stays open")
        onNodeWithTag(EditorPopupTags.COMPLETION).assertIsDisplayed()
    }

    @Test
    fun `Ctrl+M with the caret scrolled out of view reveals it and shows the list`() = runComposeUiTest {
        val ui = EditorUi(this, services())
        ui.compose(LINES, slots = HostRecordingPopups())
        ui.editor.requestFocus()
        runOnIdle { ui.state.caret.moveTo(3) }
        runOnIdle { ui.state.viewport.scrollBy(0f, 3_000f) }
        waitForIdle()

        ui.editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.M) } }
        waitForIdle()

        assertEquals(0f, ui.state.viewport.state.value.scrollY, "the explicit request revealed the caret")
        assertNotNull(ui.state.completion.state.value, "the explicit request is not lost")
        onNodeWithTag(EditorPopupTags.COMPLETION).assertIsDisplayed()
    }

    private fun services() = FakeLanguageServices(
        completions = { _, offset -> listOf(CompletionItem("observe", CompletionKind.KEYWORD, "observe", TextRange.empty(offset), null, null, null, "o")) },
    )

    /** The text of the tests. */
    private companion object {
        /** 200 short lines, so the caret can be scrolled far out of view. */
        val LINES: String = (1..200).joinToString("\n") { "line $it" }
    }
}
