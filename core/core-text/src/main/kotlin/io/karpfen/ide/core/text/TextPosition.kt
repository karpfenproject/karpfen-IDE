package io.karpfen.ide.core.text

/**
 * A zero-based line/column position in a document. [column] counts UTF-16 code units from the start of the line
 * (tab characters count as one column here; display columns are a UI concern). The UI shows both values one-based.
 */
public data class TextPosition(public val line: Int, public val column: Int) : Comparable<TextPosition> {

    init {
        require(line >= 0) { "line must be >= 0, was $line" }
        require(column >= 0) { "column must be >= 0, was $column" }
    }

    /** Orders by [line], then by [column]. */
    override fun compareTo(other: TextPosition): Int =
        if (line != other.line) line.compareTo(other.line) else column.compareTo(other.column)

    override fun toString(): String = "$line:$column"

    public companion object {
        /** The start of the document. */
        public val ORIGIN: TextPosition = TextPosition(0, 0)
    }
}
