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
package io.karpfen.ide.ui.editor.popup

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import io.karpfen.ide.ui.foundation.keys.compose.toKeyStroke

/** What a key stroke means inside a popup that owns focus, such as the find bar and go-to-line. */
internal enum class PopupKey {
    /** Enter. It runs find next, goes to the line or replaces. */
    CONFIRM,

    /** Shift+Enter. It runs find previous. */
    CONFIRM_BACKWARD,

    /** Escape. It closes the popup and returns focus to the text area. */
    CANCEL,
}

/**
 * The key policy of focused popups. It is a pure mapping. Only Enter, Shift+Enter and Escape without other modifiers
 * mean something. Every other stroke is left to the focused text field or to menu accelerators.
 */
internal object PopupKeys {

    private val bindings: Map<KeyStroke, PopupKey> = mapOf(
        KeyStroke(KeyCode.ENTER) to PopupKey.CONFIRM,
        KeyStroke(KeyCode.ENTER, setOf(KeyModifier.SHIFT)) to PopupKey.CONFIRM_BACKWARD,
        KeyStroke(KeyCode.ESCAPE) to PopupKey.CANCEL,
    )

    /** Returns the popup key of [stroke], or null. A null stroke stands for key-up events and unmapped keys and also returns null. */
    fun of(stroke: KeyStroke?): PopupKey? = stroke?.let(bindings::get)
}

/**
 * Offers the popup keys of key-down events that reach this element to [onKey]. It does so in the preview phase, before
 * a text field inside handles them. The event is consumed when [onKey] returns true. Strokes come from the shared key
 * adapter `toKeyStroke`, the same one the editor's own key handling uses.
 */
internal fun Modifier.onPopupKey(onKey: (PopupKey) -> Boolean): Modifier =
    onPreviewKeyEvent { event -> PopupKeys.of(event.toKeyStroke())?.let(onKey) ?: false }

/** Runs [action] for the popup key [key] and consumes it. Every other key is left alone. See the other [onPopupKey]. */
internal fun Modifier.onPopupKey(key: PopupKey, action: () -> Unit): Modifier =
    onPopupKey { pressed -> (pressed == key).also { matched -> if (matched) action() } }
