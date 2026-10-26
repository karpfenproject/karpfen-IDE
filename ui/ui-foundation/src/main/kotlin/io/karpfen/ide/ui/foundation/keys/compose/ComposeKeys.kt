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

/** The Compose window-menu shortcut for this shortcut on [platform] (for `MenuBar` items). */
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
