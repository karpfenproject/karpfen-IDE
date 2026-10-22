package io.karpfen.ide.core.document.dsl

import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot

/**
 * [DslErrorLocator] that searches the symbols whose names appear quoted in the message and ranks them by the message's
 * other quoted names ([QuotedNameRanking]; see [DslErrorLocator] for the rule). Quoted means between single or double
 * quotes (`'Vector'`, `"Vector"`); empty names never match, so an anonymous object does not catch every `''` of a
 * message. Ranges outside [TextSnapshot.text] (an index of another text) are ignored. Stateless and thread-safe.
 */
internal class QuotedNameErrorLocator : DslErrorLocator {

    override fun locate(message: String?, source: TextSnapshot, index: SyntaxIndex): TextRange =
        message?.let { QuotedNameRanking(it, source.length, index).best() } ?: firstToken(source.text)

    /**
     * The first token of [text]: from the first character that is neither whitespace nor part of a `//` comment through
     * the end of its word (letters, digits, `_`; a single other character, kept whole if it is a surrogate pair); the
     * empty range at 0 if there is none.
     */
    private fun firstToken(text: String): TextRange {
        var i = 0
        while (i < text.length) {
            when {
                text[i].isWhitespace() -> i++
                text.startsWith(KarpfenSyntax.LINE_COMMENT, i) -> while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
                else -> return TextRange(i, wordEnd(text, i))
            }
        }
        return TextRange.EMPTY
    }

    /** The end of the word starting at [start], or of the single character there if it is not a word character. */
    private fun wordEnd(text: String, start: Int): Int {
        if (!isWordChar(text[start])) return start + Character.charCount(text.codePointAt(start))
        var end = start + 1
        while (end < text.length && isWordChar(text[end])) end++
        return end
    }

    private fun isWordChar(char: Char): Boolean = char.isLetterOrDigit() || char == '_'
}
