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

import io.karpfen.ide.core.text.TextSnapshot

/**
 * The editor's lexical guess whether an offset is inside a `"…"` string, from the current line alone (karpfen strings
 * and the Python strings in EVAL bodies do not span lines): an odd number of `"` (not escaped by `\`) before the
 * offset on its line. Typing `"` auto-closes only outside a string (inside one it closes the string), and completion's
 * quote trigger fires only when the typed `"` opened a string. Pure functions without state.
 */
internal object StringLiterals {

    /** True if [offset] lies inside a string of its line. */
    fun isInsideString(snapshot: TextSnapshot, offset: Int): Boolean {
        val lineStart = snapshot.lineIndex.lineStart(snapshot.lineIndex.lineOf(offset))
        var inside = false
        var index = lineStart
        while (index < offset) {
            when (snapshot[index]) {
                '\\' -> if (inside) index++
                '"' -> inside = !inside
            }
            index++
        }
        return inside
    }
}
