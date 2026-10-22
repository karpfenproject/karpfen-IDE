package io.karpfen.ide.ui.editor.model.navigation

import io.karpfen.ide.core.text.TextSnapshot

/**
 * A go-to-line target as typed by the user: one-based [line] and optional one-based [column].
 */
public data class GoToLineQuery(public val line: Int, public val column: Int? = null) {
    init {
        require(line >= 1) { "line is one-based, was $line" }
        require(column == null || column >= 1) { "column is one-based, was $column" }
    }

    /** The offset in [snapshot], clamped to existing lines and columns. */
    public fun offsetIn(snapshot: TextSnapshot): Int = snapshot.offsetOf(line - 1, (column ?: 1) - 1)

    /** Parsing of the user's input. */
    public companion object {
        /** `line` or `line:col` with up to nine digits each, whitespace allowed around the numbers and the colon. */
        private val SYNTAX = Regex("""^\s*(\d{1,9})\s*(?::\s*(\d{1,9})\s*)?$""")

        /** Parses `line` or `line:col` (surrounding whitespace allowed); null for anything else or zero values. */
        public fun parse(input: String): GoToLineQuery? {
            val match = SYNTAX.matchEntire(input) ?: return null
            val line = match.groupValues[1].toInt()
            val column = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
            if (line < 1 || (column != null && column < 1)) return null
            return GoToLineQuery(line, column)
        }
    }
}
