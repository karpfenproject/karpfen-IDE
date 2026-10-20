package io.karpfen.ide.ui.foundation.keys

/**
 * Human-readable form of a shortcut for menus and tooltips: `⇧⌘F` on macOS (modifier symbols in Apple's order
 * Control, Option, Shift, Command, then the key), `Ctrl+Shift+F` elsewhere (Ctrl, Alt, Shift, then the key). Pure Kotlin
 * (the key model stays Compose-free; the Compose adapters are in `ui.foundation.keys.compose`).
 */
public fun KeyShortcut.displayText(platform: HostPlatform): String = strokeFor(platform).displayText(platform)

/**
 * Human-readable form of a physical key stroke on [platform], in the format of [KeyShortcut.displayText]; the Meta key
 * reads `⌘` on macOS, `Win` on Windows and `Super` on Linux.
 */
public fun KeyStroke.displayText(platform: HostPlatform): String = KeyStrokeFormat.of(platform).format(this)

/**
 * How one platform writes key strokes (Strategy): modifier names in their conventional order, the separator between
 * the parts and the names of the non-character keys.
 */
internal class KeyStrokeFormat private constructor(
    private val modifierNames: List<Pair<KeyModifier, String>>,
    private val separator: String,
    private val keyNames: Map<KeyCode, String>,
) {

    fun format(stroke: KeyStroke): String {
        val modifiers = modifierNames.filter { (modifier, _) -> modifier in stroke.modifiers }.map { it.second }
        return (modifiers + keyName(stroke.key)).joinToString(separator)
    }

    private fun keyName(key: KeyCode): String =
        keyNames[key] ?: PunctuationKeys.charOf(key)?.toString() ?: genericName(key)

    private fun genericName(key: KeyCode): String = key.name.removePrefix("DIGIT_")

    companion object {
        private val MAC = KeyStrokeFormat(
            modifierNames = listOf(
                KeyModifier.CTRL to "⌃",
                KeyModifier.ALT to "⌥",
                KeyModifier.SHIFT to "⇧",
                KeyModifier.META to "⌘",
            ),
            separator = "",
            keyNames = mapOf(
                KeyCode.ENTER to "↩",
                KeyCode.ESCAPE to "⎋",
                KeyCode.TAB to "⇥",
                KeyCode.BACKSPACE to "⌫",
                KeyCode.DELETE to "⌦",
                KeyCode.INSERT to "Insert",
                KeyCode.SPACE to "Space",
                KeyCode.HOME to "↖",
                KeyCode.END to "↘",
                KeyCode.PAGE_UP to "⇞",
                KeyCode.PAGE_DOWN to "⇟",
                KeyCode.LEFT to "←",
                KeyCode.RIGHT to "→",
                KeyCode.UP to "↑",
                KeyCode.DOWN to "↓",
            ),
        )

        private val PC_KEY_NAMES: Map<KeyCode, String> = mapOf(
            KeyCode.ENTER to "Enter",
            KeyCode.ESCAPE to "Esc",
            KeyCode.TAB to "Tab",
            KeyCode.BACKSPACE to "Backspace",
            KeyCode.DELETE to "Delete",
            KeyCode.INSERT to "Insert",
            KeyCode.SPACE to "Space",
            KeyCode.HOME to "Home",
            KeyCode.END to "End",
            KeyCode.PAGE_UP to "Page Up",
            KeyCode.PAGE_DOWN to "Page Down",
            KeyCode.LEFT to "Left",
            KeyCode.RIGHT to "Right",
            KeyCode.UP to "Up",
            KeyCode.DOWN to "Down",
        )

        private fun pc(metaName: String) = KeyStrokeFormat(
            modifierNames = listOf(
                KeyModifier.CTRL to "Ctrl",
                KeyModifier.ALT to "Alt",
                KeyModifier.SHIFT to "Shift",
                KeyModifier.META to metaName,
            ),
            separator = "+",
            keyNames = PC_KEY_NAMES,
        )

        private val WINDOWS = pc(metaName = "Win")
        private val LINUX = pc(metaName = "Super")

        /** The format of [platform]. */
        fun of(platform: HostPlatform): KeyStrokeFormat = when (platform) {
            HostPlatform.MAC_OS -> MAC
            HostPlatform.WINDOWS -> WINDOWS
            HostPlatform.LINUX -> LINUX
        }
    }
}
