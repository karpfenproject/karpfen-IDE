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
package io.karpfen.ide.ui.editor.view.geometry

import io.karpfen.ide.ui.editor.model.caret.TabStops

/**
 * One line as it is drawn: every tab replaced by the spaces up to its next tab stop, the width of each tab taken from
 * [TabStops] (the one tab rule of the editor), so the monospace cells of [text] are the line's display columns.
 * [displayOffsetOf] maps a column of [source] to the offset in [text] (where highlight spans start and end), with
 * [tabColumns], the columns of the tabs of [source], and [extraAfterTab], the expansion beyond one character summed up
 * to and including each tab. Immutable.
 *
 * @property source the line's text (without `\n`).
 * @property text [source] with its tabs expanded.
 */
internal class DisplayLine private constructor(
    val source: String,
    val text: String,
    private val tabColumns: IntArray,
    private val extraAfterTab: IntArray,
) {

    /**
     * The offset in [text] of [column] of [source] (clamped to the line): the column plus the expansion beyond one
     * character of every tab before it.
     */
    fun displayOffsetOf(column: Int): Int {
        val clamped = column.coerceIn(0, source.length)
        if (tabColumns.isEmpty()) return clamped
        val tabs = tabsBefore(clamped)
        return clamped + if (tabs == 0) 0 else extraAfterTab[tabs - 1]
    }

    /** The number of tabs strictly before [column], by binary search over [tabColumns]. */
    private fun tabsBefore(column: Int): Int {
        var low = 0
        var high = tabColumns.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (tabColumns[middle] < column) low = middle + 1 else high = middle
        }
        return low
    }

    /** Creation from a line's text. */
    companion object {
        /** The display form of [line] with the tab stops of [tabStops]. */
        fun of(line: String, tabStops: TabStops): DisplayLine {
            if ('\t' !in line) return DisplayLine(line, line, IntArray(0), IntArray(0))
            val tabs = line.indices.filter { line[it] == '\t' }.toIntArray()
            val extra = IntArray(tabs.size)
            val text = StringBuilder(line.length + tabs.size * tabStops.tabWidth)
            var copied = 0
            var added = 0
            tabs.forEachIndexed { index, column ->
                text.append(line, copied, column)
                val width = tabStops.displayColumn(line, column + 1) - tabStops.displayColumn(line, column)
                repeat(width) { text.append(' ') }
                added += width - 1
                extra[index] = added
                copied = column + 1
            }
            text.append(line, copied, line.length)
            return DisplayLine(line, text.toString(), tabs, extra)
        }
    }
}
