package io.karpfen.ide.ui.editor.model.caret

import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.text.TextRange

/**
 * The word rule of the editor (spec §7.1): every character belongs to one [CharClass], and a *word* is a maximal run
 * of one class — identifier runs `[A-Za-z0-9_]+`, punctuation runs and whitespace runs, as documented on
 * [CaretMovement.WORD_LEFT]. A line break is a run of its own, so word movement and word deletion stop at every line
 * boundary and a double-click never selects across lines.
 *
 * The one word rule of the editor model: word movement ([CaretModel]), word selection, word deletion and the
 * completion auto-trigger ("identifier character") all use it. Pure functions without state.
 */
internal object WordBoundaries {

    /** The classes of characters that form words. */
    enum class CharClass {
        /** `[A-Za-z0-9_]`. */
        IDENTIFIER,

        /** Whitespace other than `\n`. */
        WHITESPACE,

        /** `\n` (each one a run of its own). */
        LINE_BREAK,

        /** Everything else. */
        PUNCTUATION,
    }

    /** True for `[A-Za-z0-9_]`: the karpfen identifier characters ([KarpfenSyntax.isIdentifierChar], one source). */
    fun isIdentifierChar(char: Char): Boolean = KarpfenSyntax.isIdentifierChar(char)

    /** The class of [char]. */
    fun classOf(char: Char): CharClass = when {
        isIdentifierChar(char) -> CharClass.IDENTIFIER
        char == '\n' -> CharClass.LINE_BREAK
        char.isWhitespace() -> CharClass.WHITESPACE
        else -> CharClass.PUNCTUATION
    }

    /** The start of the run containing the character left of [offset] (0 at the start of [text]). */
    fun previousWordStart(text: CharSequence, offset: Int): Int {
        if (offset <= 0) return 0
        val runClass = classOf(text[offset - 1])
        if (runClass == CharClass.LINE_BREAK) return offset - 1
        var start = offset - 1
        while (start > 0 && classOf(text[start - 1]) == runClass) start--
        return start
    }

    /** The end of the run containing the character right of [offset] (the text length at its end). */
    fun nextWordEnd(text: CharSequence, offset: Int): Int {
        if (offset >= text.length) return text.length
        val runClass = classOf(text[offset])
        if (runClass == CharClass.LINE_BREAK) return offset + 1
        var end = offset + 1
        while (end < text.length && classOf(text[end]) == runClass) end++
        return end
    }

    /**
     * The word at [offset] for a double-click: an identifier run touching [offset] (right of it first), else the run of
     * the character right of it, else the run left of it; never a line break. Empty at [offset] on an empty line.
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
