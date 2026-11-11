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
package io.karpfen.ide.ui.editor.view.input

/**
 * Keeps a key press that the editor handled from also typing its character. A platform sends a "typed" event after the
 * key-down of a printable key. For example, Ctrl+Space, the second completion key on Windows, would type a space. When
 * the editor consumed the key-down, the typed event that follows it is swallowed as well.
 *
 * Every key-down decides again, so the gate never swallows a character of a press the editor did not handle. Commits
 * of an input method are not key events, so they always pass. Use it from the UI thread.
 */
internal class TypedKeyGate {

    private var swallowNextTyped = false

    /** Records that a key-down was routed to the editor. [handled] says whether the editor consumed it. */
    fun keyDown(handled: Boolean) {
        swallowNextTyped = handled
    }

    /** Records that a typed event arrived. Returns true if it belongs to a handled key-down and must be consumed. */
    fun typed(): Boolean {
        val swallow = swallowNextTyped
        swallowNextTyped = false
        return swallow
    }
}
