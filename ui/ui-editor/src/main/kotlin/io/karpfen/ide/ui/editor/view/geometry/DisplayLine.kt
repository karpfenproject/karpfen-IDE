package io.karpfen.ide.ui.editor.view.geometry

import io.karpfen.ide.ui.editor.model.caret.TabStops

/**
 * One line as it is drawn: every tab replaced by the spaces up to its next tab stop, the width of each tab taken from
 * [TabStops] (decision 40 of the work-package plan: the one tab rule), so the monospace cells of [text] are the line's
 * display columns. [displayOffsetOf] maps a column of [source] to the offset in [text] (where highlight spans start
 * and end). Immutable.
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

    /** The offset in [text] of [column] of [source] (clamped to the line). */
    fun displayOffsetOf(column: Int): Int {
        val clamped = column.coerceIn(0, source.length)
        if (tabColumns.isEmpty()) return clamped
        // Number of tabs strictly before the column; each adds its expansion beyond one character.
        var low = 0
        var high = tabColumns.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (tabColumns[middle] < clamped) low = middle + 1 else high = middle
        }
        return clamped + if (low == 0) 0 else extraAfterTab[low - 1]
    }

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
