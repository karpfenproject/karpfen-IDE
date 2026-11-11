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
package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyShortcut

/** A binding that makes [shortcut] run the editor action [actionId]. */
internal data class KeyBinding(val shortcut: KeyShortcut, val actionId: String)

/**
 * Returns the default editor bindings of [platform], which are the ones documented on [EditorKeymap]. The bindings of
 * one action are listed in order of preference, and menus show the first one.
 *
 * macOS follows the platform conventions. It uses Alt for words and Cmd for line and document ends. Cmd+Backspace
 * deletes the line, Cmd+G finds next and Cmd+L goes to a line. Windows and Linux use Ctrl for words, Ctrl+Home and
 * Ctrl+End, Ctrl+Y, F3 and Ctrl+G.
 *
 * Completion is Ctrl+M on every platform. On macOS this is the physical Control key too. It is the first completion
 * binding, so the Complete Code item in the window's Edit menu shows Ctrl+M everywhere. Windows binds Ctrl+Space as a
 * second completion key, because Windows users expect it. macOS and Linux leave Ctrl+Space to the system. macOS
 * switches input sources with it, and input method frameworks on Linux commonly use it to toggle the input method.
 */
internal fun defaultKeyBindings(platform: HostPlatform): List<KeyBinding> = buildList {
    val mac = platform == HostPlatform.MAC_OS
    val windows = platform == HostPlatform.WINDOWS
    movement(EditorActionIds.CARET_LEFT, EditorActionIds.SELECT_LEFT, KeyCode.LEFT)
    movement(EditorActionIds.CARET_RIGHT, EditorActionIds.SELECT_RIGHT, KeyCode.RIGHT)
    movement(EditorActionIds.CARET_UP, EditorActionIds.SELECT_UP, KeyCode.UP)
    movement(EditorActionIds.CARET_DOWN, EditorActionIds.SELECT_DOWN, KeyCode.DOWN)
    movement(EditorActionIds.CARET_WORD_LEFT, EditorActionIds.SELECT_WORD_LEFT, KeyCode.LEFT, primary = !mac, alt = mac)
    movement(EditorActionIds.CARET_WORD_RIGHT, EditorActionIds.SELECT_WORD_RIGHT, KeyCode.RIGHT, primary = !mac, alt = mac)
    movement(EditorActionIds.CARET_LINE_START, EditorActionIds.SELECT_LINE_START, KeyCode.HOME)
    movement(EditorActionIds.CARET_LINE_END, EditorActionIds.SELECT_LINE_END, KeyCode.END)
    movement(EditorActionIds.CARET_PAGE_UP, EditorActionIds.SELECT_PAGE_UP, KeyCode.PAGE_UP)
    movement(EditorActionIds.CARET_PAGE_DOWN, EditorActionIds.SELECT_PAGE_DOWN, KeyCode.PAGE_DOWN)
    movement(EditorActionIds.CARET_DOCUMENT_START, EditorActionIds.SELECT_DOCUMENT_START, KeyCode.HOME, primary = true)
    movement(EditorActionIds.CARET_DOCUMENT_END, EditorActionIds.SELECT_DOCUMENT_END, KeyCode.END, primary = true)
    if (mac) {
        movement(EditorActionIds.CARET_LINE_START, EditorActionIds.SELECT_LINE_START, KeyCode.LEFT, primary = true)
        movement(EditorActionIds.CARET_LINE_END, EditorActionIds.SELECT_LINE_END, KeyCode.RIGHT, primary = true)
        movement(EditorActionIds.CARET_DOCUMENT_START, EditorActionIds.SELECT_DOCUMENT_START, KeyCode.UP, primary = true)
        movement(EditorActionIds.CARET_DOCUMENT_END, EditorActionIds.SELECT_DOCUMENT_END, KeyCode.DOWN, primary = true)
    }
    bind(EditorActionIds.SELECT_ALL, KeyCode.A, primary = true)

    bind(EditorActionIds.ENTER, KeyCode.ENTER)
    bind(EditorActionIds.ENTER, KeyCode.ENTER, shift = true)
    bind(EditorActionIds.BACKSPACE, KeyCode.BACKSPACE)
    bind(EditorActionIds.BACKSPACE, KeyCode.BACKSPACE, shift = true)
    bind(EditorActionIds.DELETE, KeyCode.DELETE)
    bind(EditorActionIds.DELETE_WORD_LEFT, KeyCode.BACKSPACE, primary = !mac, alt = mac)
    bind(EditorActionIds.DELETE_WORD_RIGHT, KeyCode.DELETE, primary = !mac, alt = mac)
    if (mac) bind(EditorActionIds.DELETE_LINE, KeyCode.BACKSPACE, primary = true) else bind(EditorActionIds.DELETE_LINE, KeyCode.Y, primary = true)

    bind(EditorActionIds.DUPLICATE, KeyCode.D, primary = true)
    bind(EditorActionIds.MOVE_LINE_UP, KeyCode.UP, alt = true, shift = true)
    bind(EditorActionIds.MOVE_LINE_DOWN, KeyCode.DOWN, alt = true, shift = true)
    bind(EditorActionIds.INDENT, KeyCode.TAB)
    bind(EditorActionIds.OUTDENT, KeyCode.TAB, shift = true)
    bind(EditorActionIds.TOGGLE_COMMENT, KeyCode.SLASH, primary = true)

    bind(EditorActionIds.COPY, KeyCode.C, primary = true)
    bind(EditorActionIds.CUT, KeyCode.X, primary = true)
    bind(EditorActionIds.PASTE, KeyCode.V, primary = true)
    bind(EditorActionIds.UNDO, KeyCode.Z, primary = true)
    bind(EditorActionIds.REDO, KeyCode.Z, primary = true, shift = true)

    bind(EditorActionIds.FIND, KeyCode.F, primary = true)
    bind(EditorActionIds.REPLACE, KeyCode.R, primary = true)
    if (mac) {
        bind(EditorActionIds.FIND_NEXT, KeyCode.G, primary = true)
        bind(EditorActionIds.FIND_PREVIOUS, KeyCode.G, primary = true, shift = true)
        bind(EditorActionIds.GO_TO_LINE, KeyCode.L, primary = true)
    } else {
        bind(EditorActionIds.FIND_NEXT, KeyCode.F3)
        bind(EditorActionIds.FIND_PREVIOUS, KeyCode.F3, shift = true)
        bind(EditorActionIds.GO_TO_LINE, KeyCode.G, primary = true)
    }
    bind(EditorActionIds.COMPLETION, KeyCode.M, ctrl = true)
    if (windows) bind(EditorActionIds.COMPLETION, KeyCode.SPACE, ctrl = true)
    bind(EditorActionIds.GO_TO_DEFINITION, KeyCode.B, primary = true)
}

/** Adds the binding of [key] with the given modifiers to the editor action [actionId]. */
private fun MutableList<KeyBinding>.bind(
    actionId: String,
    key: KeyCode,
    primary: Boolean = false,
    shift: Boolean = false,
    alt: Boolean = false,
    ctrl: Boolean = false,
) {
    add(KeyBinding(KeyShortcut(key, primary = primary, shift = shift, alt = alt, ctrl = ctrl), actionId))
}

/** Adds the movement [moveId] on [key] and its selecting variant [selectId], which uses the same keys with Shift. */
private fun MutableList<KeyBinding>.movement(
    moveId: String,
    selectId: String,
    key: KeyCode,
    primary: Boolean = false,
    alt: Boolean = false,
) {
    bind(moveId, key, primary = primary, alt = alt)
    bind(selectId, key, primary = primary, alt = alt, shift = true)
}
