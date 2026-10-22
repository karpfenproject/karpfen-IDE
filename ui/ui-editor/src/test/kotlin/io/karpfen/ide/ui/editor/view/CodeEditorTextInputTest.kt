package io.karpfen.ide.ui.editor.view

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.state.FakeLanguageServices
import java.awt.Component
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * Typed text through the editor's real input path, the proxy text field: the platform delivers a key-down and then a
 * key-typed event carrying the produced character, here synthesized as AWT events — so the characters a keyboard layout
 * produces with Option on macOS (German: Option+8 = `{`, Option+L = `@`) arrive like any other character, a stroke the
 * editor handled does not also type its character, and punctuation shortcuts follow the layout. Dead keys and input
 * methods use the same field's composition support (manual test).
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class CodeEditorTextInputTest {

    private val source = object : Component() {}

    private fun awtModifiers(alt: Boolean, ctrl: Boolean, shift: Boolean, meta: Boolean): Int =
        (if (alt) AwtKeyEvent.ALT_DOWN_MASK else 0) or (if (ctrl) AwtKeyEvent.CTRL_DOWN_MASK else 0) or
            (if (shift) AwtKeyEvent.SHIFT_DOWN_MASK else 0) or (if (meta) AwtKeyEvent.META_DOWN_MASK else 0)

    /** The key-down of [key] (AWT [code]) producing [char] with the given modifiers. */
    private fun keyDown(
        key: Key,
        code: Int,
        char: Char,
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
    ) = KeyEvent(
        key, KeyEventType.KeyDown, codePoint = char.code,
        isAltPressed = alt, isCtrlPressed = ctrl, isShiftPressed = shift, isMetaPressed = meta,
        nativeEvent = AwtKeyEvent(source, AwtKeyEvent.KEY_PRESSED, 0L, awtModifiers(alt, ctrl, shift, meta), code, char),
    )

    /** The key-typed event of [char]. */
    private fun keyTyped(char: Char, alt: Boolean = false, ctrl: Boolean = false) = KeyEvent(
        Key.Unknown, KeyEventType.Unknown, codePoint = char.code, isAltPressed = alt, isCtrlPressed = ctrl,
        nativeEvent = AwtKeyEvent(source, AwtKeyEvent.KEY_TYPED, 0L, awtModifiers(alt, ctrl, false, false), AwtKeyEvent.VK_UNDEFINED, char),
    )

    @Test
    fun `typed characters reach the editor through the proxy text field`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose(" x")
        ui.editor.requestFocus()

        ui.editor.performKeyPress(keyDown(Key.A, AwtKeyEvent.VK_A, 'a'))
        ui.editor.performKeyPress(keyTyped('a'))
        ui.editor.performKeyPress(keyDown(Key.Eight, AwtKeyEvent.VK_8, '{', alt = true))
        ui.editor.performKeyPress(keyTyped('{', alt = true))
        ui.editor.performKeyPress(keyDown(Key.L, AwtKeyEvent.VK_L, '@', alt = true))
        ui.editor.performKeyPress(keyTyped('@', alt = true))
        waitForIdle()

        assertEquals("a{@} x", ui.plain, "Option+8 types '{' (auto-closed), Option+L types '@'")
        assertEquals(3, ui.state.caret.primary.position)
    }

    @Test
    fun `a stroke the editor handled does not type its character`() = runComposeUiTest {
        val items = { offset: Int ->
            listOf(CompletionItem("STATE", CompletionKind.KEYWORD, "STATE", TextRange.empty(offset), null, null, null, "s"))
        }
        val ui = EditorUi(this, FakeLanguageServices(completions = { _, offset -> items(offset) }))
        ui.compose("")
        ui.editor.requestFocus()

        ui.editor.performKeyPress(keyDown(Key.Spacebar, AwtKeyEvent.VK_SPACE, ' ', ctrl = true))
        ui.editor.performKeyPress(keyTyped(' ', ctrl = true))
        waitForIdle()

        assertEquals("", ui.plain, "Ctrl+Space opened completion and typed no space")
        assertNotNull(ui.state.completion.state.value)
    }

    @Test
    fun `punctuation shortcuts follow the layout - German Cmd+Shift+7 toggles a comment`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose("STATE")
        ui.editor.requestFocus()

        ui.editor.performKeyPress(keyDown(Key.Seven, AwtKeyEvent.VK_7, '/', shift = true, meta = true))
        waitForIdle()

        assertEquals("// STATE", ui.plain)
    }
}
