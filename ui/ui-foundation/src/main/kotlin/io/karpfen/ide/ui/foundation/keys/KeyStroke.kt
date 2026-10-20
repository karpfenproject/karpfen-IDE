package io.karpfen.ide.ui.foundation.keys

/**
 * Toolkit-neutral keys for shortcuts. Compose-free on purpose: the editor's keymap and command lookups live in
 * Compose-free model code (spec §1a, §7.1); `ComposeKeys.kt` converts Compose key events.
 *
 * Letters, digits, function and navigation keys denote physical keys. The punctuation codes ([SLASH] … [RIGHT_BRACKET])
 * denote the *character* (slash, backslash, minus, equals, comma, period, semicolon, apostrophe, grave accent,
 * brackets) on the current keyboard layout (decision 41 of the work-package plan): the key adapter reports the code of
 * the character a key produced, without the Shift the layout needed for it. On a German layout Shift+7 produces `/`,
 * so Cmd+Shift+7 arrives as `KeyStroke(SLASH, {META})` and matches the binding primary+/ (toggle comment) like on a US
 * layout.
 */
public enum class KeyCode {
    A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4, DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    ENTER, ESCAPE, TAB, BACKSPACE, DELETE, INSERT, SPACE,
    HOME, END, PAGE_UP, PAGE_DOWN, LEFT, RIGHT, UP, DOWN,
    SLASH, BACKSLASH, MINUS, EQUALS, COMMA, PERIOD, SEMICOLON, APOSTROPHE, GRAVE, LEFT_BRACKET, RIGHT_BRACKET,
}

/** A physical modifier key. */
public enum class KeyModifier {
    /** Shift. */
    SHIFT,

    /** Control (Ctrl on every platform, including macOS). */
    CTRL,

    /** Alt / Option. */
    ALT,

    /** Meta / Command (macOS) / Windows key. */
    META,
}

/** A key press with the exact set of physical modifiers held (what a key event reports). */
public data class KeyStroke(public val key: KeyCode, public val modifiers: Set<KeyModifier> = emptySet()) {
    override fun toString(): String = (modifiers.sorted().map { it.name } + key.name).joinToString("+")
}

/** The desktop platform; decides which physical modifier is the "primary" one (Cmd on macOS, Ctrl elsewhere). */
public enum class HostPlatform {
    /** macOS. */
    MAC_OS,

    /** Windows. */
    WINDOWS,

    /** Linux and other Unix-like systems. */
    LINUX,
    ;

    /** The primary shortcut modifier: [KeyModifier.META] on macOS, [KeyModifier.CTRL] elsewhere. */
    public val primaryModifier: KeyModifier get() = if (this == MAC_OS) KeyModifier.META else KeyModifier.CTRL

    public companion object {
        /** The platform of this JVM, from the `os.name` system property. */
        public fun current(): HostPlatform = fromOsName(System.getProperty("os.name").orEmpty())

        /** The platform for an `os.name` value (unknown names count as [LINUX]). */
        public fun fromOsName(osName: String): HostPlatform {
            val name = osName.lowercase()
            return when {
                name.startsWith("mac") || name.contains("darwin") -> MAC_OS
                name.startsWith("windows") -> WINDOWS
                else -> LINUX
            }
        }
    }
}

/**
 * A platform-independent shortcut: [primary] means Cmd on macOS and Ctrl elsewhere (spec §6). [ctrl] requests the
 * physical Ctrl key in addition (only meaningful on macOS, where it differs from the primary modifier).
 */
public data class KeyShortcut(
    public val key: KeyCode,
    public val primary: Boolean = false,
    public val shift: Boolean = false,
    public val alt: Boolean = false,
    public val ctrl: Boolean = false,
) {
    /** The physical key stroke that triggers this shortcut on [platform]. */
    public fun strokeFor(platform: HostPlatform): KeyStroke {
        val modifiers = buildSet {
            if (primary) add(platform.primaryModifier)
            if (ctrl) add(KeyModifier.CTRL)
            if (shift) add(KeyModifier.SHIFT)
            if (alt) add(KeyModifier.ALT)
        }
        return KeyStroke(key, modifiers)
    }
}
