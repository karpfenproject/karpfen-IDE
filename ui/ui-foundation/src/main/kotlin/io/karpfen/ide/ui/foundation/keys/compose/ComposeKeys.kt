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
 *
 * Layout-aware punctuation (MUST, see `KeyCode`): when the event produces one of the punctuation characters of
 * `KeyCode` on the current layout — from the event's code point, or, where modifiers suppress it (Cmd/Ctrl held), from
 * the native AWT event (`nativeKeyEvent`: key char or extended key code) — the stroke's key is that punctuation code
 * and SHIFT is dropped if the layout needed it for the character (German Cmd+Shift+7 → `SLASH` + META); otherwise the
 * physical key and modifiers are reported unchanged (US Shift+/ produces `?` and stays `SLASH` + SHIFT). Headless
 * tests cover both layouts with synthesized events; "Cmd+/ on a German layout" is in the manual test.
 */
public fun KeyEvent.toKeyStroke(): KeyStroke? = TODO("WP-FOUNDATION: map Compose key events to key strokes")

/** The Compose window-menu shortcut for this shortcut on [platform] (for `MenuBar` items). */
public fun KeyShortcut.toMenuShortcut(platform: HostPlatform): ComposeKeyShortcut =
    TODO("WP-FOUNDATION: Compose menu shortcut")
