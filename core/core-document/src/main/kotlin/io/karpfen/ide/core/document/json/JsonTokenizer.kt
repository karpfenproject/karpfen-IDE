package io.karpfen.ide.core.document.json

import io.karpfen.ide.core.document.syntax.SyntaxToken
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.TextSnapshot

/**
 * The [Tokenizer] of JSON documents over [JsonLexer]: strings become [TokenKind.STRING], numbers [TokenKind.NUMBER],
 * `true`, `false` and `null` [TokenKind.KEYWORD], brackets and separators [TokenKind.PUNCTUATION] and INVALID tokens
 * [TokenKind.ERROR], each with its LEXER error. JSON has no comments and no embedded code. Stateless and thread-safe.
 */
public class JsonTokenizer : Tokenizer {

    override fun tokenize(snapshot: TextSnapshot): TokenizeResult {
        val lexed = JsonLexer.lex(snapshot.text)
        return TokenizeResult(snapshot.version, lexed.tokens.map { SyntaxToken(it.range, kindOf(it.kind)) }, lexed.diagnostics)
    }

    /** The syntax token kind of a JSON token kind. */
    private fun kindOf(kind: JsonTokenKind): TokenKind = when {
        kind == JsonTokenKind.STRING -> TokenKind.STRING
        kind == JsonTokenKind.NUMBER -> TokenKind.NUMBER
        kind.isLiteral -> TokenKind.KEYWORD
        kind.isStructural -> TokenKind.PUNCTUATION
        else -> TokenKind.ERROR
    }
}
