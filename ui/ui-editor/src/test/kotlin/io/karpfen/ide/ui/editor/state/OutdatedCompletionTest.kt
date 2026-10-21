package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.foundation.keys.KeyCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A completion result that arrives after the text moved on opens nothing where no trigger fired (spec §7.1: completion
 * auto-triggers only on an identifier character after one, `"`, `$(` and `->`; regression of the review finding
 * "stale-completion-rerequest-opens-popup"). The editor's background dispatcher is the test scheduler, so an
 * auto-triggered request is still running when the next key arrives, as during fast typing in the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutdatedCompletionTest {

    /** A source that offers STATE at every offset (an empty replace range there), like the keyword list of a body. */
    private fun everywhere() = FakeLanguageServices(
        completions = { _, offset -> listOf(CompletionItem("STATE", CompletionKind.KEYWORD, "STATE", TextRange.empty(offset), null, null, null, "STATE")) },
    )

    @Test
    fun `a request outdated by a line break opens nothing on the new line`() = editorTest("", services = everywhere()) { harness ->
        harness.state.type("S")
        harness.state.type("T")
        harness.press(KeyCode.ENTER)
        advanceUntilIdle()

        assertNull(harness.state.completion.state.value, "no trigger fired at the start of the new line")
        harness.press(KeyCode.ENTER)
        assertEquals("ST\n\n", harness.plain, "the second Enter breaks the line instead of accepting an item")
    }

    @Test
    fun `a request outdated by a space opens nothing after the space`() = editorTest("", services = everywhere()) { harness ->
        harness.state.type("S")
        harness.state.type("T")
        harness.state.type(" ")
        advanceUntilIdle()

        assertNull(harness.state.completion.state.value)
    }

    @Test
    fun `a request outdated by an edit elsewhere is repeated at the caret`() = editorTest("", services = everywhere()) { harness ->
        harness.state.type("S")
        harness.state.type("T")
        harness.text.apply(listOf(TextEdit.insert(0, "x\n")), EditOrigin.External)
        advanceUntilIdle()

        val open = assertNotNull(harness.state.completion.state.value, "the caret is still where the request completes")
        assertEquals(TextRange.empty(4), open.replaceRange, "ranges of the current text")
    }
}
