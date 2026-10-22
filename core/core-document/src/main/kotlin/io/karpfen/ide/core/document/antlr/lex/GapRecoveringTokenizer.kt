package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.KarpfenLexers
import io.karpfen.ide.core.document.antlr.LexerGaps
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.TextSnapshot

/**
 * The tokenizing algorithm shared by KMeta and KModel, whose lexers `skip` whitespace and `//` comments:
 *
 * 1. lex with `KarpfenLexers.lex` (IDE listener; lexer errors become LEXER diagnostics) and classify every emitted
 *    token with [kinds], ranges from `LexedText.rangeOf`;
 * 2. recover what the lexer dropped with `LexerGaps.scan`: COMMENT pieces become [TokenKind.COMMENT] tokens,
 *    UNRECOGNIZED pieces [TokenKind.ERROR] tokens (their diagnostics are the listener's);
 * 3. merge both by position.
 *
 * Every non-whitespace character ends up in exactly one token: the gaps contain only whitespace, comments and
 * unrecognized input by construction of the two grammars.
 *
 * One refinement of step 2: a COMMENT piece that overlaps a LEXER error is an [TokenKind.ERROR] token. ANTLR's error
 * recovery consumes the character at which a token failed, so in `ty//pe` the first `/` ends the failed `type` and
 * the lexer never sees a comment, while the gap scanner, which only looks at characters, reports `//pe` as one. With
 * the refinement every ERROR token overlaps a LEXER diagnostic and vice versa (lexer errors always become ERROR tokens
 * plus ERROR diagnostics), which the token-invariant tests check on every mutation.
 *
 * Stateless and thread-safe; the language tokenizers wrap it in a [FailSafeTokenizer].
 */
internal class GapRecoveringTokenizer(
    private val language: KarpfenLanguage,
    private val kinds: TokenKindTable,
) : Tokenizer {

    override fun tokenize(snapshot: TextSnapshot): TokenizeResult {
        val text = snapshot.text
        val lexed = KarpfenLexers.lex(language, text)
        val lexemes = lexed.tokens.mapNotNull { token -> lexed.rangeOf(token)?.let { range -> range to kinds.kindOf(token.type) } }
        val gaps = LexerGaps.scan(text, lexemes.map { (range, _) -> range })
        val lexerErrors = RangeOverlapIndex(lexed.diagnostics.map { it.range })

        val tokens = TokenSequence()
        var nextGap = 0
        fun addGap(gap: LexerGaps.GapPiece) = tokens.add(gap.range, kindOf(gap, lexerErrors))
        for ((range, kind) in lexemes) {
            while (nextGap < gaps.size && gaps[nextGap].range.start < range.start) addGap(gaps[nextGap++])
            tokens.add(range, kind)
        }
        gaps.subList(nextGap, gaps.size).forEach(::addGap)

        return TokenizeResult(snapshot.version, tokens.toList(), lexed.diagnostics)
    }

    /** The token kind of [gap]: ERROR for unrecognized input and for a comment that overlaps a lexer error. */
    private fun kindOf(gap: LexerGaps.GapPiece, lexerErrors: RangeOverlapIndex): TokenKind = when {
        gap.kind == LexerGaps.GapKind.UNRECOGNIZED -> TokenKind.ERROR
        lexerErrors.overlapsAny(gap.range) -> TokenKind.ERROR
        else -> TokenKind.COMMENT
    }
}
