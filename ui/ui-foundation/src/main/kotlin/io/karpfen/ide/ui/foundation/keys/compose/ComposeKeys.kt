package io.karpfen.ide.ui.foundation.keys.compose

import androidx.compose.ui.input.key.KeyEvent
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyShortcut
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import androidx.compose.ui.input.key.KeyShortcut as ComposeKeyShortcut

/**
 * The [KeyStroke] of a Compose key-down event (physical modifiers as reported by the event), or null for key-up
 * events, unmapped keys and lone modifier presses. The Compose adapter of the Compose-free key model in
 * `ui.foundation.keys` (which never depends on this package).
 */
public fun KeyEvent.toKeyStroke(): KeyStroke? = TODO("WP-FOUNDATION: map Compose key events to key strokes")

/** The Compose window-menu shortcut for this shortcut on [platform] (for `MenuBar` items). */
public fun KeyShortcut.toMenuShortcut(platform: HostPlatform): ComposeKeyShortcut =
    TODO("WP-FOUNDATION: Compose menu shortcut")
