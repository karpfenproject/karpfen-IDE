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

import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.TextRange

/**
 * A caret with its selection. [position] is where the caret blinks, and [anchor] is the fixed end of the selection.
 * When both are equal, there is no selection. [preferredColumn] is the column that vertical movement tries to keep. It
 * is a display column with tabs expanded. It is null when the next vertical move should take it from [position].
 */
public data class Caret(public val anchor: Int, public val position: Int, public val preferredColumn: Int? = null) {
    init {
        require(anchor >= 0 && position >= 0) { "offsets must be >= 0, was anchor=$anchor position=$position" }
    }

    /** The selected range. It is empty when there is no selection. */
    public val selection: TextRange get() = TextRange(minOf(anchor, position), maxOf(anchor, position))

    /** True if text is selected. */
    public val hasSelection: Boolean get() = anchor != position

    /** The undo memento of this caret. */
    public fun toMemento(): CaretMemento = CaretMemento(anchor, position)

    /** Factories of carets. */
    public companion object {
        /** A caret at [offset] without selection. */
        public fun at(offset: Int): Caret = Caret(offset, offset)

        /** The caret stored in [memento]. It has no preferred column. */
        public fun of(memento: CaretMemento): Caret = Caret(memento.anchor, memento.caret)
    }
}

/**
 * The carets of an editor at one text [version]. The editor uses a single caret, but the list leaves room for multiple
 * carets. [carets] is never empty, and its first caret is the primary one.
 */
public data class CaretSnapshot(public val version: Long, public val carets: List<Caret>) {
    init {
        require(carets.isNotEmpty()) { "there is always at least one caret" }
    }

    /** The primary caret. */
    public val primary: Caret get() = carets.first()
}

/** Caret movements. */
public enum class CaretMovement {
    /** One character left. When a selection collapses, the caret goes to the selection start. */
    LEFT,

    /** One character right. When a selection collapses, the caret goes to the selection end. */
    RIGHT,

    /** To the previous word start. A word is a run of `[A-Za-z0-9_]`, a run of punctuation or a run of whitespace. */
    WORD_LEFT,

    /** To the next word end, with the same words as [WORD_LEFT]. */
    WORD_RIGHT,

    /** One line up, keeping the preferred column. */
    UP,

    /** One line down, keeping the preferred column. */
    DOWN,

    /** Smart Home. It goes to the first non-whitespace character of the line, or to column 0 if the caret is already there. */
    LINE_START,

    /** End of the line. */
    LINE_END,

    /** One page up. A page is the number of visible lines. */
    PAGE_UP,

    /** One page down. */
    PAGE_DOWN,

    /** Start of the document. */
    DOCUMENT_START,

    /** End of the document. */
    DOCUMENT_END,
}
