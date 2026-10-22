package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The key routing of the editor state: `handleKey` asks the completion session first, then the keymap, and returns true
 * exactly for the strokes it handled. The completion keys per platform: Ctrl+M everywhere, Ctrl+Space on Windows only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyRoutingTest {

    private fun items(offset: Int) = listOf("alpha", "beta", "gamma").map {
        CompletionItem(it, CompletionKind.KEYWORD, it, TextRange.empty(offset), null, null, null, it)
    }

    private fun completing() = FakeLanguageServices(completions = { _, offset -> items(offset) })

    private fun typingAlpha() = FakeLanguageServices(
        completions = { _, offset ->
            listOf(CompletionItem("alpha", CompletionKind.KEYWORD, "alpha", TextRange(0, offset), null, null, null, "a"))
        },
    )

    @Test
    fun `completion handles its keys before the keymap`() = editorTest("abc\ndef", services = completing()) { harness ->
        harness.state.caret.moveTo(3)
        assertTrue(harness.press(KeyCode.M, KeyModifier.CTRL), "Ctrl+M requests completion")
        advanceUntilIdle()
        assertNotNull(harness.state.completion.state.value)

        assertTrue(harness.press(KeyCode.DOWN))

        assertEquals(1, harness.state.completion.state.value?.selectedIndex)
        assertEquals(3, harness.state.caret.primary.position, "Down moved the completion selection, not the caret")
        assertTrue(harness.press(KeyCode.ENTER), "Enter accepts")
        assertEquals("abcbeta\ndef", harness.plain)
        assertNull(harness.state.completion.state.value)
    }

    @ParameterizedTest
    @EnumSource(HostPlatform::class)
    fun `Ctrl+M requests completion on every platform`(platform: HostPlatform) =
        editorTest("abc", platform = platform, services = completing()) { harness ->
            harness.state.caret.moveTo(3)

            assertTrue(harness.press(KeyCode.M, KeyModifier.CTRL), "handled on $platform")
            advanceUntilIdle()

            assertEquals(listOf("alpha", "beta", "gamma"), harness.state.completion.state.value?.items?.map { it.label })
            assertEquals("abc", harness.plain, "nothing typed")
        }

    @ParameterizedTest
    @EnumSource(HostPlatform::class)
    fun `Ctrl+Space requests completion on Windows only and goes on unhandled elsewhere`(platform: HostPlatform) =
        editorTest("abc", platform = platform, services = completing()) { harness ->
            harness.state.caret.moveTo(3)
            val windows = platform == HostPlatform.WINDOWS

            assertEquals(windows, harness.press(KeyCode.SPACE, KeyModifier.CTRL), "handled on $platform")
            advanceUntilIdle()

            assertEquals(windows, harness.state.completion.state.value != null, "completion open on $platform")
        }

    @Test
    fun `Escape is handled only while completion is open`() = editorTest("abc", services = completing()) { harness ->
        harness.state.execute(EditorActionIds.COMPLETION)
        advanceUntilIdle()

        assertTrue(harness.press(KeyCode.ESCAPE))
        assertNull(harness.state.completion.state.value)
        assertFalse(harness.press(KeyCode.ESCAPE), "nothing to close: the stroke goes on")
    }

    @Test
    fun `a bound and enabled action is handled, unbound and disabled strokes are not`() = editorTest("abc") { harness ->

        assertTrue(harness.press(KeyCode.RIGHT))
        assertEquals(1, harness.state.caret.primary.position)
        assertFalse(harness.press(KeyCode.F12), "unbound")
        assertFalse(harness.press(KeyCode.Z, KeyModifier.META), "undo without history is disabled")
        assertFalse(harness.press(KeyCode.S, KeyModifier.META), "Save belongs to the window menu")
        assertTrue(harness.press(KeyCode.BACKSPACE))
        assertEquals("bc", harness.plain)
        assertTrue(harness.press(KeyCode.Z, KeyModifier.META), "undo is enabled now")
        assertEquals("abc", harness.plain)
    }

    @Test
    fun `the keymap of the editor's platform decides`() = editorTest("one two", platform = HostPlatform.WINDOWS) { windows ->

        assertTrue(windows.press(KeyCode.RIGHT, KeyModifier.CTRL), "word right is Ctrl+Right on Windows")
        assertEquals(3, windows.state.caret.primary.position)
        assertFalse(windows.press(KeyCode.Z, KeyModifier.META), "Meta is no primary modifier on Windows")
    }

    @Test
    fun `typing goes through the typing action and auto-triggers completion`() = editorTest("", services = typingAlpha()) { harness ->
        harness.state.type("a")
        harness.state.type("l")
        advanceUntilIdle()

        assertEquals("al", harness.plain)
        assertEquals("alpha", harness.state.completion.state.value?.selected?.label, "an identifier character after one opens it")
        assertTrue(harness.press(KeyCode.TAB))
        assertEquals("alpha", harness.plain)
    }
}
