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
import io.karpfen.ide.ui.foundation.keys.HostPlatform
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

    /** Language services that offer the keyword `STATE` at every offset. */
    private fun completing() = FakeLanguageServices(
        completions = { _, offset ->
            listOf(CompletionItem("STATE", CompletionKind.KEYWORD, "STATE", TextRange.empty(offset), null, null, null, "s"))
        },
    )

    @Test
    fun `a stroke the editor handled does not type its character - Ctrl+Space on Windows`() = runComposeUiTest {
        val ui = EditorUi(this, completing(), HostPlatform.WINDOWS)
        ui.compose("")
        ui.editor.requestFocus()

        ui.editor.performKeyPress(keyDown(Key.Spacebar, AwtKeyEvent.VK_SPACE, ' ', ctrl = true))
        ui.editor.performKeyPress(keyTyped(' ', ctrl = true))
        waitForIdle()

        assertEquals("", ui.plain, "Ctrl+Space opened completion and typed no space")
        assertNotNull(ui.state.completion.state.value)
    }

    @Test
    fun `Ctrl+M opens completion and types nothing`() = runComposeUiTest {
        val ui = EditorUi(this, completing())
        ui.compose("")
        ui.editor.requestFocus()

        ui.editor.performKeyPress(keyDown(Key.M, AwtKeyEvent.VK_M, CONTROL_M, ctrl = true))
        ui.editor.performKeyPress(keyTyped(CONTROL_M, ctrl = true))
        waitForIdle()

        assertEquals("", ui.plain, "Ctrl+M typed no line break")
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

    /** The characters of the tests. */
    private companion object {
        /** The control character a platform reports for Ctrl+M: carriage return, as the terminal convention has it. */
        const val CONTROL_M = '\r'
    }
}
