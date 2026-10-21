package io.karpfen.ide.ui.editor.model.caret

/** Columns per tab stop unless the editor settings say otherwise (spec §7.2). */
public const val DEFAULT_TAB_WIDTH: Int = 4

/**
 * Display columns with tab stops (spec §7.2: a tab advances to the next multiple of [tabWidth]): the one conversion
 * between a column of a line (UTF-16 code units from the line start, as in `TextPosition`) and the column the user
 * sees. The caret model (preferred column for Up/Down), the editor view (rendering, hit-testing, caret x) and the
 * status bar ("Col y") all use it, so a remembered column means the same everywhere (decision 40 of the work-package
 * plan). Complete in the contracts step.
 *
 * Every character but a tab advances one display column; a surrogate pair counts as one (it renders as one glyph);
 * a tab advances to the next multiple of [tabWidth].
 */
public data class TabStops(public val tabWidth: Int = DEFAULT_TAB_WIDTH) {

    init {
        require(tabWidth >= 1) { "tabWidth must be >= 1, was $tabWidth" }
    }

    /** The display column of [column] (clamped to `0..line.length`) in [line] (one line's text, without `\n`). */
    public fun displayColumn(line: CharSequence, column: Int): Int {
        val end = column.coerceIn(0, line.length)
        var display = 0
        for (i in 0 until end) {
            display = advance(line, i, display)
        }
        return display
    }

    /**
     * The display columns of [columns] (ascending, each clamped to `0..line.length` like [displayColumn]) in [line], in
     * one pass over the line up to the last of them: [displayColumn] for many positions of one line (e.g. the bounds of
     * every find match on it) at the cost of one call, not one per position.
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
     * The column of [line] whose display column is nearest to [displayColumn]: inside a tab, the nearer of its two
     * bounds (the left one on a tie); never between the two halves of a surrogate pair; [line]'s length beyond its end
     * and 0 for negative values.
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

    private fun advance(line: CharSequence, index: Int, display: Int): Int {
        val char = line[index]
        return when {
            char == '\t' -> (display / tabWidth + 1) * tabWidth
            Character.isLowSurrogate(char) && index > 0 && Character.isHighSurrogate(line[index - 1]) -> display
            else -> display + 1
        }
    }

    private fun isSurrogatePairAt(line: CharSequence, index: Int): Boolean =
        Character.isHighSurrogate(line[index]) && index + 1 < line.length && Character.isLowSurrogate(line[index + 1])
}
