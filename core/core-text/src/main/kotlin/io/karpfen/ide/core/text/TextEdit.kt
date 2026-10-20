package io.karpfen.ide.core.text

/**
 * Replaces the text in [range] by [newText]. Ranges are expressed in the coordinates of the text *before* the edit
 * (see [EditableText.apply] for how several edits combine).
 */
public data class TextEdit(public val range: TextRange, public val newText: String) {

    /** Change of the document length caused by this edit. */
    public val lengthDelta: Int get() = newText.length - range.length

    /** True if the edit changes nothing (empty range, empty text). */
    public val isNoOp: Boolean get() = range.isEmpty && newText.isEmpty()

    /** True if the edit only inserts text. */
    public val isInsertion: Boolean get() = range.isEmpty && newText.isNotEmpty()

    /** True if the edit only deletes text. */
    public val isDeletion: Boolean get() = !range.isEmpty && newText.isEmpty()

    public companion object {
        /** Inserts [text] at [offset]. */
        public fun insert(offset: Int, text: String): TextEdit = TextEdit(TextRange.empty(offset), text)

        /** Deletes [range]. */
        public fun delete(range: TextRange): TextEdit = TextEdit(range, "")

        /** Deletes `[start, end)`. */
        public fun delete(start: Int, end: Int): TextEdit = TextEdit(TextRange(start, end), "")

        /** Replaces [range] by [text]. */
        public fun replace(range: TextRange, text: String): TextEdit = TextEdit(range, text)
    }
}
