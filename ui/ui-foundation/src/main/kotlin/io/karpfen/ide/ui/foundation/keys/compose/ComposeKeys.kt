package io.karpfen.ide.ui.foundation.keys

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyShortcut as ComposeKeyShortcut

/**
 * The [KeyStroke] of a Compose key-down event (physical modifiers as reported by the event), or null for key-up
 * events, unmapped keys and lone modifier presses. Bridges Compose input to the Compose-free keymaps.
 */
public fun KeyEvent.toKeyStroke(): KeyStroke? = TODO("WP-FOUNDATION: map Compose key events to key strokes")

/** Human-readable form of a shortcut for menus and tooltips: `⌘⇧F` on macOS, `Ctrl+Shift+F` elsewhere. */
public fun KeyShortcut.displayText(platform: HostPlatform): String = TODO("WP-FOUNDATION: shortcut display text")

/** The Compose window-menu shortcut for this shortcut on [platform] (for `MenuBar` items). */
public fun KeyShortcut.toMenuShortcut(platform: HostPlatform): ComposeKeyShortcut =
    TODO("WP-FOUNDATION: Compose menu shortcut")
