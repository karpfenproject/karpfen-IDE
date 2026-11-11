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
package io.karpfen.ide.ui.editor.model.navigation

import io.karpfen.ide.core.text.TextSnapshot

/**
 * A go-to-line target as the user typed it. [line] is one-based, and the optional [column] is one-based too.
 */
public data class GoToLineQuery(public val line: Int, public val column: Int? = null) {
    init {
        require(line >= 1) { "line is one-based, was $line" }
        require(column == null || column >= 1) { "column is one-based, was $column" }
    }

    /** Returns the offset of the target in [snapshot], clamped to the existing lines and columns. */
    public fun offsetIn(snapshot: TextSnapshot): Int = snapshot.offsetOf(line - 1, (column ?: 1) - 1)

    /** Parses the user's input. */
    public companion object {
        /** Matches `line` or `line:col` with up to nine digits each. Whitespace is allowed around the numbers and the colon. */
        private val SYNTAX = Regex("""^\s*(\d{1,9})\s*(?::\s*(\d{1,9})\s*)?$""")

        /**
         * Parses `line` or `line:col`, with whitespace allowed around them. Returns null for any other input and for
         * zero values.
         */
        public fun parse(input: String): GoToLineQuery? {
            val match = SYNTAX.matchEntire(input) ?: return null
            val line = match.groupValues[1].toInt()
            val column = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
            if (line < 1 || (column != null && column < 1)) return null
            return GoToLineQuery(line, column)
        }
    }
}
