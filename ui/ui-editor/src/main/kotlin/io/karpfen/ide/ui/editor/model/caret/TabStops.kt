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
package io.karpfen.ide.ui.editor.model.caret

/** Columns per tab stop unless the editor settings say otherwise. */
public const val DEFAULT_TAB_WIDTH: Int = 4

/**
 * Display columns with tab stops. This is the only conversion between a column of a line and the column the user sees.
 * A column of a line counts UTF-16 code units from the line start, as in `TextPosition`.
 *
 * The caret model uses it for the preferred column of Up and Down. The editor view uses it for rendering, hit-testing
 * and the caret's x position. The status bar uses it for its "Col" value. So a remembered column means the same
 * everywhere. It lives in the editor module because display columns are a UI concern. The text model counts code units
 * only.
 *
 * Every character except a tab advances one display column. A surrogate pair counts as one, because it renders as one
 * glyph. A tab advances to the next multiple of [tabWidth].
 */
public data class TabStops(public val tabWidth: Int = DEFAULT_TAB_WIDTH) {

    init {
        require(tabWidth >= 1) { "tabWidth must be >= 1, was $tabWidth" }
    }

    /**
     * Returns the display column of [column] in [line]. [line] is the text of one line without `\n`. [column] is
     * clamped to `0..line.length`.
     */
    public fun displayColumn(line: CharSequence, column: Int): Int {
        val end = column.coerceIn(0, line.length)
        var display = 0
        for (i in 0 until end) {
            display = advance(line, i, display)
        }
        return display
    }

    /**
     * Returns the display columns of [columns] in [line]. [columns] must be ascending, and each one is clamped to
     * `0..line.length` like in [displayColumn]. It makes one pass over the line up to the last column. So it answers
     * [displayColumn] for many positions of one line at the cost of one call, for example for the bounds of every find
     * match on the line.
     */
    public fun displayColumns(line: CharSequence, columns: IntArray): IntArray {
        val result = IntArray(columns.size)
        var index = 0
        var display = 0
        for (k in columns.indices) {
            val end = columns[k].coerceIn(0, line.length)
            require(end >= index) { "columns must be ascending: ${columns[k]} after column $index" }
            while (index < end) {
                display = advance(line, index, display)
                index++
            }
            result[k] = display
        }
        return result
    }

    /**
     * Returns the column of [line] whose display column is nearest to [displayColumn]. Inside a tab it returns the
     * nearer of the tab's two bounds, and the left one on a tie. It never returns a column between the two halves of a
     * surrogate pair. Beyond the end of the line it returns the line length, and for negative values it returns 0.
     */
    public fun columnAt(line: CharSequence, displayColumn: Int): Int {
        var display = 0
        var index = 0
        while (index < line.length) {
            if (displayColumn <= display) return index
            val next = if (isSurrogatePairAt(line, index)) index + 2 else index + 1
            val nextDisplay = advance(line, index, display)
            if (displayColumn < nextDisplay) {
                return if (displayColumn - display <= nextDisplay - displayColumn) index else next
            }
            display = nextDisplay
            index = next
        }
        return line.length
    }

    /** The display column after the character at [index] of [line], which starts at display column [display]. */
    private fun advance(line: CharSequence, index: Int, display: Int): Int {
        val char = line[index]
        return when {
            char == '\t' -> (display / tabWidth + 1) * tabWidth
            Character.isLowSurrogate(char) && index > 0 && Character.isHighSurrogate(line[index - 1]) -> display
            else -> display + 1
        }
    }

    /** Whether a surrogate pair starts at [index] of [line]. */
    private fun isSurrogatePairAt(line: CharSequence, index: Int): Boolean =
        Character.isHighSurrogate(line[index]) && index + 1 < line.length && Character.isLowSurrogate(line[index + 1])
}
