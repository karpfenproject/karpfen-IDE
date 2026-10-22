package io.karpfen.ide.ui.foundation.keys

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The display text of shortcuts and strokes per platform: macOS symbols in Apple's order, spelled-out modifiers joined
 * by plus elsewhere, punctuation characters, digits and the names of navigation, editing and meta keys.
 */
class KeyShortcutTextTest {

    @Test
    fun `macOS uses modifier symbols in Apple's order without separators`() {
        val find = KeyShortcut(KeyCode.F, primary = true, shift = true)

        assertEquals("⌘F", KeyShortcut(KeyCode.F, primary = true).displayText(HostPlatform.MAC_OS))
        assertEquals("⇧⌘F", find.displayText(HostPlatform.MAC_OS))
        assertEquals(
            "⌃⌥⇧⌘K",
            KeyShortcut(KeyCode.K, primary = true, shift = true, alt = true, ctrl = true).displayText(HostPlatform.MAC_OS),
        )
        assertEquals("⌃Space", KeyShortcut(KeyCode.SPACE, ctrl = true).displayText(HostPlatform.MAC_OS))
    }

    @Test
    fun `Windows and Linux spell modifiers out joined by plus`() {
        val find = KeyShortcut(KeyCode.F, primary = true, shift = true)

        assertEquals("Ctrl+Shift+F", find.displayText(HostPlatform.WINDOWS))
        assertEquals("Ctrl+Shift+F", find.displayText(HostPlatform.LINUX))
        assertEquals(
            "Ctrl+Alt+Shift+K",
            KeyShortcut(KeyCode.K, primary = true, shift = true, alt = true).displayText(HostPlatform.LINUX),
        )
        assertEquals("F3", KeyShortcut(KeyCode.F3).displayText(HostPlatform.WINDOWS))
        assertEquals("Shift+F3", KeyShortcut(KeyCode.F3, shift = true).displayText(HostPlatform.WINDOWS))
    }

    @Test
    fun `physical Ctrl and the primary modifier collapse into one Ctrl outside macOS`() {
        assertEquals("Ctrl+Space", KeyShortcut(KeyCode.SPACE, ctrl = true, primary = true).displayText(HostPlatform.WINDOWS))
        assertEquals("⌃⌘Space", KeyShortcut(KeyCode.SPACE, ctrl = true, primary = true).displayText(HostPlatform.MAC_OS))
    }

    @Test
    fun `punctuation codes show their character and digits their number`() {
        assertEquals("⌘/", KeyShortcut(KeyCode.SLASH, primary = true).displayText(HostPlatform.MAC_OS))
        assertEquals("Ctrl+/", KeyShortcut(KeyCode.SLASH, primary = true).displayText(HostPlatform.LINUX))
        assertEquals("Ctrl+\\", KeyShortcut(KeyCode.BACKSLASH, primary = true).displayText(HostPlatform.LINUX))
        assertEquals("Ctrl+=", KeyShortcut(KeyCode.EQUALS, primary = true).displayText(HostPlatform.LINUX))
        assertEquals("Ctrl+'", KeyShortcut(KeyCode.APOSTROPHE, primary = true).displayText(HostPlatform.LINUX))
        assertEquals("Ctrl+`", KeyShortcut(KeyCode.GRAVE, primary = true).displayText(HostPlatform.LINUX))
        assertEquals("Ctrl+[", KeyShortcut(KeyCode.LEFT_BRACKET, primary = true).displayText(HostPlatform.LINUX))
        assertEquals("Ctrl+]", KeyShortcut(KeyCode.RIGHT_BRACKET, primary = true).displayText(HostPlatform.LINUX))
        assertEquals("Ctrl+1", KeyShortcut(KeyCode.DIGIT_1, primary = true).displayText(HostPlatform.LINUX))
        assertEquals("⌘0", KeyShortcut(KeyCode.DIGIT_0, primary = true).displayText(HostPlatform.MAC_OS))
    }

    @Test
    fun `navigation and editing keys have platform names`() {
        val mac = listOf(
            KeyCode.ENTER to "↩", KeyCode.ESCAPE to "⎋", KeyCode.TAB to "⇥", KeyCode.BACKSPACE to "⌫",
            KeyCode.DELETE to "⌦", KeyCode.HOME to "↖", KeyCode.END to "↘", KeyCode.PAGE_UP to "⇞",
            KeyCode.PAGE_DOWN to "⇟", KeyCode.LEFT to "←", KeyCode.RIGHT to "→", KeyCode.UP to "↑", KeyCode.DOWN to "↓",
        )
        val pc = listOf(
            KeyCode.ENTER to "Enter", KeyCode.ESCAPE to "Esc", KeyCode.TAB to "Tab", KeyCode.BACKSPACE to "Backspace",
            KeyCode.DELETE to "Delete", KeyCode.INSERT to "Insert", KeyCode.SPACE to "Space", KeyCode.HOME to "Home",
            KeyCode.END to "End", KeyCode.PAGE_UP to "Page Up", KeyCode.PAGE_DOWN to "Page Down",
            KeyCode.LEFT to "Left", KeyCode.RIGHT to "Right", KeyCode.UP to "Up", KeyCode.DOWN to "Down",
        )

        mac.forEach { (key, text) -> assertEquals(text, KeyShortcut(key).displayText(HostPlatform.MAC_OS), "$key") }
        pc.forEach { (key, text) -> assertEquals(text, KeyShortcut(key).displayText(HostPlatform.WINDOWS), "$key") }
    }

    @Test
    fun `every key code has a non-blank display text on every platform`() {
        for (platform in HostPlatform.entries) {
            for (key in KeyCode.entries) {
                val text = KeyShortcut(key).displayText(platform)
                assertEquals(false, text.isBlank(), "$key on $platform")
                assertEquals(false, text.contains('_'), "$key on $platform reads '$text'")
            }
        }
    }

    @Test
    fun `a stroke with the meta key names it per platform`() {
        val stroke = KeyStroke(KeyCode.E, setOf(KeyModifier.META, KeyModifier.SHIFT))

        assertEquals("⇧⌘E", stroke.displayText(HostPlatform.MAC_OS))
        assertEquals("Shift+Win+E", stroke.displayText(HostPlatform.WINDOWS))
        assertEquals("Shift+Super+E", stroke.displayText(HostPlatform.LINUX))
    }
}
