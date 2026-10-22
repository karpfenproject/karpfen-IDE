package io.karpfen.ide.ui.foundation.keys.compose

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import io.karpfen.ide.ui.foundation.keys.KeyShortcut
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import java.awt.Component
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import androidx.compose.ui.input.key.KeyShortcut as ComposeKeyShortcut
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * The Compose key adapter on synthesized events: the physical key table, modifiers, and the layout-aware punctuation
 * rule fed from the event's code point and from its native AWT event. [ComposeKeyInputTest] runs the same adapter
 * through a composed, focused component. Synthesizing events needs Compose's `KeyEvent(…)` factory, which is marked
 * internal-unstable (the test framework uses it the same way); production code never constructs key events.
 */
@OptIn(InternalComposeUiApi::class)
class ComposeKeysTest {

    private fun down(
        key: Key,
        char: Char? = null,
        shift: Boolean = false,
        ctrl: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
        native: AwtKeyEvent? = null,
    ): KeyEvent = KeyEvent(
        key = key,
        type = KeyEventType.KeyDown,
        codePoint = char?.code ?: 0,
        isCtrlPressed = ctrl,
        isMetaPressed = meta,
        isAltPressed = alt,
        isShiftPressed = shift,
        nativeEvent = native,
    )

    private val source = object : Component() {}

    /** A synthesized AWT key press; AWT leaves its extended key code unset (only native events carry one). */
    private fun awtPressed(keyCode: Int, keyChar: Char, modifiers: Int): AwtKeyEvent =
        AwtKeyEvent(source, AwtKeyEvent.KEY_PRESSED, 0L, modifiers, keyCode, keyChar, AwtKeyEvent.KEY_LOCATION_STANDARD)

    @Test
    fun `every key code maps to a Compose key and back`() {
        for (code in KeyCode.entries) {
            val composeKey = ComposeKeyTable.composeKeyOf(code)
            assertEquals(code, ComposeKeyTable.codeOf(composeKey), "$code")
            assertEquals(KeyStroke(code), down(composeKey).toKeyStroke(), "$code without a character")
        }
    }

    @Test
    fun `modifiers are reported as held`() {
        assertEquals(KeyStroke(KeyCode.S, setOf(KeyModifier.META)), down(Key.S, 's', meta = true).toKeyStroke())
        assertEquals(KeyStroke(KeyCode.S, setOf(KeyModifier.CTRL)), down(Key.S, ctrl = true).toKeyStroke())
        assertEquals(
            KeyStroke(KeyCode.F, setOf(KeyModifier.CTRL, KeyModifier.ALT, KeyModifier.SHIFT, KeyModifier.META)),
            down(Key.F, ctrl = true, alt = true, shift = true, meta = true).toKeyStroke(),
        )
        assertEquals(KeyStroke(KeyCode.F3, setOf(KeyModifier.SHIFT)), down(Key.F3, shift = true).toKeyStroke())
    }

    @Test
    fun `key-up events, lone modifiers and unmapped keys have no stroke`() {
        assertNull(KeyEvent(Key.S, KeyEventType.KeyUp, codePoint = 's'.code).toKeyStroke())
        assertNull(down(Key.ShiftLeft, shift = true).toKeyStroke())
        assertNull(down(Key.MetaRight, meta = true).toKeyStroke())
        assertNull(down(Key.CtrlLeft, ctrl = true).toKeyStroke())
        assertNull(down(Key.AltLeft, alt = true).toKeyStroke())
        assertNull(down(Key.NumPad5, '5').toKeyStroke())
        assertNull(down(Key.CapsLock).toKeyStroke())
    }

    @Test
    fun `the keypad Enter is Enter and the keypad slash is the slash character`() {
        assertEquals(KeyStroke(KeyCode.ENTER), down(Key.NumPadEnter).toKeyStroke())
        assertEquals(KeyStroke(KeyCode.SLASH, setOf(KeyModifier.CTRL)), down(Key.NumPadDivide, '/', ctrl = true).toKeyStroke())
    }

    @Test
    fun `German Shift+7 producing a slash is SLASH without Shift`() {
        assertEquals(KeyStroke(KeyCode.SLASH), down(Key.Seven, '/', shift = true).toKeyStroke())
    }

    @Test
    fun `German Cmd+Shift+7 is SLASH with META`() {
        assertEquals(KeyStroke(KeyCode.SLASH, setOf(KeyModifier.META)), down(Key.Seven, '/', shift = true, meta = true).toKeyStroke())
    }

    @Test
    fun `US Shift+slash is SLASH with SHIFT`() {
        assertEquals(KeyStroke(KeyCode.SLASH, setOf(KeyModifier.SHIFT)), down(Key.Slash, '?', shift = true).toKeyStroke())
        assertEquals(KeyStroke(KeyCode.SLASH, setOf(KeyModifier.SHIFT)), down(Key.Slash, shift = true).toKeyStroke())
    }

    @Test
    fun `US Cmd+slash is SLASH with META`() {
        assertEquals(KeyStroke(KeyCode.SLASH, setOf(KeyModifier.META)), down(Key.Slash, '/', meta = true).toKeyStroke())
    }

    @Test
    fun `the native key char is used when the code point is suppressed`() {
        val native = awtPressed(AwtKeyEvent.VK_7, '/', AwtKeyEvent.META_DOWN_MASK or AwtKeyEvent.SHIFT_DOWN_MASK)

        val stroke = down(Key.Seven, char = null, shift = true, meta = true, native = native).toKeyStroke()

        assertEquals(KeyStroke(KeyCode.SLASH, setOf(KeyModifier.META)), stroke)
    }

    /** On Windows, Ctrl+/ reports the control character 0x1F as key char; the key code still names the slash key. */
    @Test
    fun `the native key code is used when Ctrl turns the key char into a control character`() {
        val native = awtPressed(AwtKeyEvent.VK_SLASH, '\u001f', AwtKeyEvent.CTRL_DOWN_MASK)

        val stroke = down(Key.Slash, char = '\u001f', ctrl = true, native = native).toKeyStroke()

        assertEquals(KeyStroke(KeyCode.SLASH, setOf(KeyModifier.CTRL)), stroke)
    }

    @Test
    fun `a keypad minus with Ctrl is the minus character`() {
        val native = awtPressed(AwtKeyEvent.VK_SUBTRACT, AwtKeyEvent.CHAR_UNDEFINED, AwtKeyEvent.CTRL_DOWN_MASK)

        val stroke = down(Key.NumPadSubtract, ctrl = true, native = native).toKeyStroke()

        assertEquals(KeyStroke(KeyCode.MINUS, setOf(KeyModifier.CTRL)), stroke)
    }

    /** For a layout key without a VK constant, AWT reports 0x01000000 plus the key's unshifted character. */
    @Test
    fun `extended key codes name a VK punctuation key or a Unicode key's character`() {
        assertEquals('-', ProducedCharacter.ofExtendedKeyCode(0x01000000 + '-'.code))
        assertEquals('/', ProducedCharacter.ofExtendedKeyCode(AwtKeyEvent.VK_SLASH))
        assertEquals('[', ProducedCharacter.ofExtendedKeyCode(AwtKeyEvent.VK_OPEN_BRACKET))
        assertNull(ProducedCharacter.ofExtendedKeyCode(0x01000000 + 0x1f))
        assertNull(ProducedCharacter.ofExtendedKeyCode(AwtKeyEvent.VK_7))
        assertNull(ProducedCharacter.ofExtendedKeyCode(AwtKeyEvent.VK_UNDEFINED))
    }

    /**
     * Ctrl+Shift+7 on a German Windows layout carries no key char, and the key's own character is `7`; the manual test
     * lists this limitation.
     */
    @Test
    fun `a native event without any character falls back to the physical key`() {
        val native = awtPressed(AwtKeyEvent.VK_7, AwtKeyEvent.CHAR_UNDEFINED, AwtKeyEvent.CTRL_DOWN_MASK or AwtKeyEvent.SHIFT_DOWN_MASK)

        val stroke = down(Key.Seven, ctrl = true, shift = true, native = native).toKeyStroke()

        assertEquals(KeyStroke(KeyCode.DIGIT_7, setOf(KeyModifier.CTRL, KeyModifier.SHIFT)), stroke)
    }

    @Test
    fun `without a native event the physical key decides`() {
        assertEquals(
            KeyStroke(KeyCode.DIGIT_7, setOf(KeyModifier.CTRL, KeyModifier.SHIFT)),
            down(Key.Seven, ctrl = true, shift = true).toKeyStroke(),
        )
    }

    @Test
    fun `menu shortcuts use Cmd on macOS and Ctrl elsewhere`() {
        val save = KeyShortcut(KeyCode.S, primary = true)
        val saveAll = KeyShortcut(KeyCode.S, primary = true, alt = true)
        val completion = KeyShortcut(KeyCode.SPACE, ctrl = true)

        assertEquals(ComposeKeyShortcut(Key.S, meta = true), save.toMenuShortcut(HostPlatform.MAC_OS))
        assertEquals(ComposeKeyShortcut(Key.S, ctrl = true), save.toMenuShortcut(HostPlatform.WINDOWS))
        assertEquals(ComposeKeyShortcut(Key.S, ctrl = true, alt = true), saveAll.toMenuShortcut(HostPlatform.LINUX))
        assertEquals(ComposeKeyShortcut(Key.Spacebar, ctrl = true), completion.toMenuShortcut(HostPlatform.MAC_OS))
        assertEquals(
            ComposeKeyShortcut(Key.Slash, meta = true, shift = true),
            KeyShortcut(KeyCode.SLASH, primary = true, shift = true).toMenuShortcut(HostPlatform.MAC_OS),
        )
    }

    @Test
    fun `every key code has a menu shortcut`() {
        for (code in KeyCode.entries) {
            assertEquals(
                ComposeKeyShortcut(ComposeKeyTable.composeKeyOf(code), ctrl = true),
                KeyShortcut(code, primary = true).toMenuShortcut(HostPlatform.LINUX),
            )
        }
    }
}
