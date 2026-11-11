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
package io.karpfen.ide.ui.foundation.keys.compose

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import io.karpfen.ide.ui.foundation.keys.KeyShortcut
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import io.karpfen.ide.ui.foundation.keys.LayoutAwareStrokes
import androidx.compose.ui.input.key.KeyShortcut as ComposeKeyShortcut

/**
 * The [KeyStroke] of a Compose key-down event, with the physical modifiers the event reports. Returns null for key-up
 * events, unmapped keys and lone modifier presses. This is the Compose adapter of the key model in
 * `ui.foundation.keys`, which itself never depends on this package.
 *
 * Punctuation is layout-aware, as described on `KeyCode`. The adapter first finds the character the event produced on
 * the current layout. It takes the event's code point. When modifiers suppress the code point, as a held Cmd or Ctrl
 * does, it falls back to the native AWT event (`nativeKeyEvent`) and uses its key char or its extended key code. If
 * that character is one of the punctuation characters of `KeyCode`, the stroke's key is its punctuation code. SHIFT is
 * then dropped when the layout needed it for the character, so German Cmd+Shift+7 becomes `SLASH` with META.
 * Otherwise the physical key and the modifiers are reported unchanged, so US Shift+/ produces `?` and stays `SLASH`
 * with SHIFT. Headless tests cover both layouts with synthesized events. Cmd+/ on a real German layout can only be
 * checked by hand.
 *
 * A native event can carry no character at all. Ctrl+Shift+7 on a German Windows layout is such a case, and its key's
 * own character is `7`. The adapter then reports the physical key, so Toggle Comment cannot be reached with that stroke
 * on that layout. This is a known limitation.
 */
public fun KeyEvent.toKeyStroke(): KeyStroke? {
    if (type != KeyEventType.KeyDown) return null
    val modifiers = buildSet {
        if (isShiftPressed) add(KeyModifier.SHIFT)
        if (isCtrlPressed) add(KeyModifier.CTRL)
        if (isAltPressed) add(KeyModifier.ALT)
        if (isMetaPressed) add(KeyModifier.META)
    }
    return LayoutAwareStrokes.resolve(ComposeKeyTable.codeOf(key), modifiers, ProducedCharacter.of(this))
}

/** The Compose shortcut of this shortcut on [platform], for the items of the window's `MenuBar`. */
public fun KeyShortcut.toMenuShortcut(platform: HostPlatform): ComposeKeyShortcut {
    val stroke = strokeFor(platform)
    return ComposeKeyShortcut(
        key = ComposeKeyTable.composeKeyOf(stroke.key),
        ctrl = KeyModifier.CTRL in stroke.modifiers,
        meta = KeyModifier.META in stroke.modifiers,
        alt = KeyModifier.ALT in stroke.modifiers,
        shift = KeyModifier.SHIFT in stroke.modifiers,
    )
}
