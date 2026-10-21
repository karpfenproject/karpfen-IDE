package io.karpfen.ide.ui.editor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.completion.CompletionTrigger
import io.karpfen.ide.ui.editor.popup.EditorPopupTags
import io.karpfen.ide.ui.editor.state.FakeLanguageServices
import io.karpfen.ide.ui.editor.view.EditorUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A completion list that cannot be shown takes no keys, with the real `EditorPopups` over `CodeEditor` (regression of
 * the review finding "hidden-completion-captures-keys"): scrolling the caret out of view closes the session, so Enter
 * breaks the line instead of accepting an item nobody sees.
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
}
