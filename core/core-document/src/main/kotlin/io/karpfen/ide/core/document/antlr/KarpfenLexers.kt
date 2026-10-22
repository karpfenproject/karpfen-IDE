package io.karpfen.ide.core.document.antlr

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.text.TextRange
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.Lexer
import org.antlr.v4.runtime.Token

/**
 * Creates the dsl-tools lexers the IDE way: ANTLR's console listener removed, an [IdeErrorListener] attached, so lexer
 * errors become diagnostics instead of console output. The tokenizers lex with [lex]; the tree walkers build their
 * `CommonTokenStream` on [create].
 */
internal object KarpfenLexers {

    /** The generated lexer of [grammar] over [input], with every error listener replaced by [listener]. */
    fun create(grammar: DslGrammar, input: CharStream, listener: IdeErrorListener): Lexer = listener.attachTo(grammar.lexer(input))

    /** Lexes all of [text] with [grammar]; never throws (lexer errors become [LexedText.diagnostics]). */
    fun lex(grammar: DslGrammar, text: String): LexedText {
        val offsets = CodePointOffsets.of(text)
        val listener = IdeErrorListener(offsets)
        val tokens = create(grammar, CharStreams.fromString(text), listener).allTokens.toList()
        return LexedText(text, offsets, tokens, listener.diagnostics)
    }
}

/**
 * The result of lexing one text.
 *
 * @property text the lexed text.
 * @property offsets the conversion of the lexer's code-point indices into offsets of [text].
 * @property tokens every emitted token of every channel in input order, without EOF. Tokens the grammar `skip`s are
 *   absent (KMeta/KModel whitespace and comments, see [LexerGaps]); KStates whitespace and `//` comments are on the
 *   HIDDEN channel and present.
 * @property diagnostics LEXER errors, sorted with [Diagnostic.ORDER].
 */
internal class LexedText(
    val text: String,
    val offsets: CodePointOffsets,
    val tokens: List<Token>,
    val diagnostics: List<Diagnostic>,
) {
    /** The IDE range of [token] (null for conjured tokens). */
    fun rangeOf(token: Token): TextRange? = offsets.range(token.startIndex, token.stopIndex)
}
