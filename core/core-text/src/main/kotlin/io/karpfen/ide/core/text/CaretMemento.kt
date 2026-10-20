package io.karpfen.ide.core.text

/**
 * A caret (and its selection) as stored in undo history: [caret] is the caret offset, [anchor] the other end of the
 * selection (`anchor == caret` without selection). Offsets refer to the text version the memento was taken in.
 */
public data class CaretMemento(public val anchor: Int, public val caret: Int) {
    init {
        require(anchor >= 0 && caret >= 0) { "offsets must be >= 0, was anchor=$anchor caret=$caret" }
    }

    /** The selected range (empty if there is no selection). */
    public val selection: TextRange get() = TextRange(minOf(anchor, caret), maxOf(anchor, caret))

    public companion object {
        /** A caret at [offset] without selection. */
        public fun at(offset: Int): CaretMemento = CaretMemento(offset, offset)
    }
}
