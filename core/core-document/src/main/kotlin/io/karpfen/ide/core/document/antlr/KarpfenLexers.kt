package io.karpfen.ide.core.document.antlr

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.text.TextRange
import kmeta.KmetaLexer
import kmodel.KmodelLexer
import kstates.KstatesLexer
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.Lexer
import org.antlr.v4.runtime.Token

/**
 * Creates the dsl-tools lexers the IDE way: ANTLR's console listener removed, an [IdeErrorListener] attached, so lexer
 * errors become diagnostics instead of console output. The only place that maps a [KarpfenLanguage] to its generated
 * lexer class (a fourth language adds its entry here). The tokenizers lex with [lex]; the tree walkers build their
 * `CommonTokenStream` on [create].
 */
internal object KarpfenLexers {

    /**
     * The generated lexer of [language] over [input], with every error listener replaced by [listener].
     *
     * @throws IllegalArgumentException for environment descriptors, which have no ANTLR grammar: the JSON tokenizer of
     *   the `json` package reads them.
     */
    fun create(language: KarpfenLanguage, input: CharStream, listener: IdeErrorListener): Lexer {
        val lexer: Lexer = when (language) {
            KarpfenLanguage.KMETA -> KmetaLexer(input)
            KarpfenLanguage.KMODEL -> KmodelLexer(input)
            KarpfenLanguage.KSTATES -> KstatesLexer(input)
            KarpfenLanguage.ENVIRONMENT -> throw IllegalArgumentException("environment descriptors have no ANTLR lexer")
        }
        return listener.attachTo(lexer)
    }

    /** Lexes all of [text] as [language]; never throws (lexer errors become [LexedText.diagnostics]). */
    fun lex(language: KarpfenLanguage, text: String): LexedText {
        val offsets = CodePointOffsets.of(text)
        val listener = IdeErrorListener(offsets)
        val tokens = create(language, CharStreams.fromString(text), listener).allTokens.toList()
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
