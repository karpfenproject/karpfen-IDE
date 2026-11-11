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
 * The editor's lexical guess whether an offset lies inside a `"..."` string. It looks at the current line only,
 * because karpfen strings and the Python strings in EVAL bodies never span lines. An offset is inside a string when an
 * odd number of `"` precede it on its line, not counting quotes escaped by `\`.
 *
 * Two features depend on this guess. Typing `"` auto-closes only outside a string, because inside one the quote closes
 * the string. The quote trigger of completion fires only when the typed `"` opened a string. The object holds pure
 * functions and no state.
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
