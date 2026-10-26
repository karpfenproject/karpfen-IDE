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
import io.karpfen.ide.ui.editor.model.caret.TabStops

/**
 * The indentation rules of the editing actions: one indentation level is [SIZE] spaces; auto-indent, smart Backspace,
 * Tab and Shift+Tab work in columns of [stops] (tabs in existing indentation expand to the same 4-column stops). Pure
 * functions without state.
 */
internal object Indentation {

    /** Columns of one indentation level. */
    const val SIZE: Int = 4

    /** The text of one indentation level. */
    val UNIT: String = " ".repeat(SIZE)

    /** Display columns of indentation. */
    val stops: TabStops = TabStops(SIZE)

    /** True for the characters of indentation (space and tab). */
    fun isIndentChar(char: Char): Boolean = char == ' ' || char == '\t'

    /** The leading spaces and tabs of [text]. */
    fun leadingWhitespace(text: CharSequence): String {
        var end = 0
        while (end < text.length && isIndentChar(text[end])) end++
        return text.subSequence(0, end).toString()
    }

    /** The offset of the first character of [line] that is not a space or tab (the line end for a blank line). */
    fun firstNonBlank(snapshot: TextSnapshot, line: Int): Int =
        snapshot.lineIndex.lineStart(line) + leadingWhitespace(snapshot.lineText(line)).length

    /** Spaces from display column [column] to the next indentation stop (1..[SIZE]). */
    fun spacesToNextStop(column: Int): String = " ".repeat(SIZE - column % SIZE)
}
