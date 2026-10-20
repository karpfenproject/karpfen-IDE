package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.KarpfenLexers
import io.karpfen.ide.core.document.antlr.LexerGaps
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.TextSnapshot

/**
 * The tokenizing algorithm shared by KMeta and KModel (spec §3.1), whose lexers `skip` whitespace and `//` comments:
 *
 * 1. lex with `KarpfenLexers.lex` (IDE listener; lexer errors become LEXER diagnostics) and classify every emitted
 *    token with [kinds], ranges from `LexedText.rangeOf`;
 * 2. recover what the lexer dropped with `LexerGaps.scan`: COMMENT pieces become [TokenKind.COMMENT] tokens,
 *    UNRECOGNIZED pieces [TokenKind.ERROR] tokens (their diagnostics are the listener's);
 * 3. merge both by position.
 *
 * Every non-whitespace character ends up in exactly one token: the gaps contain only whitespace, comments and
 * unrecognized input by construction of the two grammars. Stateless and thread-safe; the language tokenizers wrap it
 * in a [FailSafeTokenizer].
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

        val tokens = TokenSequence()
        var nextGap = 0
        for ((range, kind) in lexemes) {
            while (nextGap < gaps.size && gaps[nextGap].range.start < range.start) {
                tokens.add(gaps[nextGap].range, kindOf(gaps[nextGap].kind))
                nextGap++
            }
            tokens.add(range, kind)
        }
        for (gap in gaps.subList(nextGap, gaps.size)) tokens.add(gap.range, kindOf(gap.kind))

        return TokenizeResult(snapshot.version, tokens.toList(), lexed.diagnostics)
    }

    private fun kindOf(gap: LexerGaps.GapKind): TokenKind = when (gap) {
        LexerGaps.GapKind.COMMENT -> TokenKind.COMMENT
        LexerGaps.GapKind.UNRECOGNIZED -> TokenKind.ERROR
    }
}
