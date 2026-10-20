package io.karpfen.ide.ui.foundation.keys

/**
 * Human-readable form of a shortcut for menus and tooltips: `⌘⇧F` on macOS, `Ctrl+Shift+F` elsewhere. Pure Kotlin (the
 * key model stays Compose-free; the Compose adapters are in `ui.foundation.keys.compose`).
 */
public fun KeyShortcut.displayText(platform: HostPlatform): String = TODO("WP-FOUNDATION: shortcut display text")
