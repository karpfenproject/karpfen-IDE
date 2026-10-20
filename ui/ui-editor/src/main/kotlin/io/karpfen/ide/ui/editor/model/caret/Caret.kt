package io.karpfen.ide.ui.editor.model.caret

import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.TextRange

/**
 * A caret with its selection (spec §7.1): [position] is where the caret blinks, [anchor] the fixed end of the
 * selection (`anchor == position`: no selection). [preferredColumn] is the column vertical movement tries to keep
 * (display column, tabs expanded), or null when the next vertical move should take it from [position].
 */
public data class Caret(public val anchor: Int, public val position: Int, public val preferredColumn: Int? = null) {
    init {
        require(anchor >= 0 && position >= 0) { "offsets must be >= 0, was anchor=$anchor position=$position" }
    }

    /** The selected range (empty without selection). */
    public val selection: TextRange get() = TextRange(minOf(anchor, position), maxOf(anchor, position))

    /** True if text is selected. */
    public val hasSelection: Boolean get() = anchor != position

    /** The undo memento of this caret. */
    public fun toMemento(): CaretMemento = CaretMemento(anchor, position)

    public companion object {
        /** A caret at [offset] without selection. */
        public fun at(offset: Int): Caret = Caret(offset, offset)

        /** The caret stored in [memento] (no preferred column). */
        public fun of(memento: CaretMemento): Caret = Caret(memento.anchor, memento.caret)
    }
}

/**
 * The carets of an editor at one text [version] (spec §7.1: one caret in phase 1, list-ready for multi-caret).
 * [carets] is non-empty; the first caret is the primary one.
 */
public data class CaretSnapshot(public val version: Long, public val carets: List<Caret>) {
    init {
        require(carets.isNotEmpty()) { "there is always at least one caret" }
    }

    /** The primary caret. */
    public val primary: Caret get() = carets.first()
}

/** Caret movements (spec §7.1). */
public enum class CaretMovement {
    /** One character left (to the selection start when a selection is collapsed). */
    LEFT,

    /** One character right (to the selection end when a selection is collapsed). */
    RIGHT,

    /** To the previous word start; words are `[A-Za-z0-9_]+`, punctuation runs and whitespace runs. */
    WORD_LEFT,

    /** To the next word end (same word classes as [WORD_LEFT]). */
    WORD_RIGHT,

    /** One line up, keeping the preferred column. */
    UP,

    /** One line down, keeping the preferred column. */
    DOWN,

    /** Smart Home: first non-whitespace character of the line, then column 0. */
    LINE_START,

    /** End of the line. */
    LINE_END,

    /** One page up (page = visible lines). */
    PAGE_UP,

    /** One page down. */
    PAGE_DOWN,

    /** Start of the document. */
    DOCUMENT_START,

    /** End of the document. */
    DOCUMENT_END,
}
