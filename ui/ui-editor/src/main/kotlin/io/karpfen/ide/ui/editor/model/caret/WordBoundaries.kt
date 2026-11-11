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

import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.text.TextRange

/**
 * The word rule of the editor. Every character belongs to one [CharClass], and a word is a longest run of characters
 * of one class. So there are identifier runs of `[A-Za-z0-9_]`, punctuation runs and whitespace runs, as documented on
 * [CaretMovement.WORD_LEFT]. A line break is a run of its own. Word movement and word deletion therefore stop at every
 * line boundary, and a double-click never selects across lines.
 *
 * This is the only word rule of the editor model. Word movement in the [CaretModel], word selection, word deletion and
 * the identifier check of the completion auto-trigger all use it. The object holds pure functions and no state.
 */
internal object WordBoundaries {

    /** The classes of characters that form words. */
    enum class CharClass {
        /** `[A-Za-z0-9_]`. */
        IDENTIFIER,

        /** Whitespace other than `\n`. */
        WHITESPACE,

        /** `\n`. Each line break is a run of its own. */
        LINE_BREAK,

        /** Everything else. */
        PUNCTUATION,
    }

    /**
     * True for `[A-Za-z0-9_]`, the karpfen identifier characters. It delegates to [KarpfenSyntax.isIdentifierChar], so
     * there is one source for this rule.
     */
    fun isIdentifierChar(char: Char): Boolean = KarpfenSyntax.isIdentifierChar(char)

    /** The class of [char]. */
    fun classOf(char: Char): CharClass = when {
        isIdentifierChar(char) -> CharClass.IDENTIFIER
        char == '\n' -> CharClass.LINE_BREAK
        char.isWhitespace() -> CharClass.WHITESPACE
        else -> CharClass.PUNCTUATION
    }

    /** Returns the start of the run that contains the character left of [offset]. At the start of [text] it returns 0. */
    fun previousWordStart(text: CharSequence, offset: Int): Int {
        if (offset <= 0) return 0
        val runClass = classOf(text[offset - 1])
        if (runClass == CharClass.LINE_BREAK) return offset - 1
        var start = offset - 1
        while (start > 0 && classOf(text[start - 1]) == runClass) start--
        return start
    }

    /** Returns the end of the run that contains the character right of [offset]. At the end of [text] it returns its length. */
    fun nextWordEnd(text: CharSequence, offset: Int): Int {
        if (offset >= text.length) return text.length
        val runClass = classOf(text[offset])
        if (runClass == CharClass.LINE_BREAK) return offset + 1
        var end = offset + 1
        while (end < text.length && classOf(text[end]) == runClass) end++
        return end
    }

    /**
     * Returns the word at [offset] for a double-click. An identifier run that touches [offset] wins, and the one to the
     * right is checked first. Otherwise it is the run of the character right of [offset], and after that the run left of
     * it. A line break is never selected. On an empty line the result is the empty range at [offset].
     */
    fun wordAt(text: CharSequence, offset: Int): TextRange {
        val right = text.getOrNull(offset)?.takeIf { it != '\n' }
        val left = text.getOrNull(offset - 1)?.takeIf { it != '\n' }
        val index = when {
            right != null && isIdentifierChar(right) -> offset
            left != null && isIdentifierChar(left) -> offset - 1
            right != null -> offset
            left != null -> offset - 1
            else -> return TextRange.empty(offset)
        }
        val runClass = classOf(text[index])
        var start = index
        while (start > 0 && classOf(text[start - 1]) == runClass) start--
        var end = index + 1
        while (end < text.length && classOf(text[end]) == runClass) end++
        return TextRange(start, end)
    }
}
