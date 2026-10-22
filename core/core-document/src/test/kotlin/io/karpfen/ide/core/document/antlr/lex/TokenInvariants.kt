package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.EvalBodies
import io.karpfen.ide.core.document.antlr.KarpfenLexers
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The tokenizer of the modeling language [language] as the registry wires it (the classes of this package).
 *
 * @throws IllegalArgumentException for environment descriptors, whose JSON tokenizer the tests of the `json` package
 *   cover.
 */
internal fun tokenizerOf(language: KarpfenLanguage): Tokenizer = when (language) {
    KarpfenLanguage.KMETA -> KmetaTokenizer()
    KarpfenLanguage.KMODEL -> KmodelTokenizer()
    KarpfenLanguage.KSTATES -> KstatesTokenizer()
    KarpfenLanguage.ENVIRONMENT -> throw IllegalArgumentException("environment descriptors are no ANTLR language")
}

/** The whitespace of the karpfen grammars (`[ \t\r\n]`); every other character must be in a token. */
internal fun isGrammarWhitespace(char: Char): Boolean = char == ' ' || char == '\t' || char == '\r' || char == '\n'

/**
 * Tokenizes [text] as [language] (version 7) and asserts the token invariants of the [Tokenizer] contract and of
 * `TokenizeResult` (see [assertTokenInvariants]); returns the result. [label] identifies the input in failure messages.
 */
internal fun tokenizeChecked(language: KarpfenLanguage, text: String, label: String): TokenizeResult {
    val snapshot = TextSnapshot(text, 7)
    val result = try {
        tokenizerOf(language).tokenize(snapshot)
    } catch (failure: Exception) {
        fail("tokenizing threw for $label: $failure", failure)
    }
    assertTokenInvariants(language, text, result, label)
    return result
}

/**
 * The token invariants of the [Tokenizer] contract, checked on every example, mutation and prefix:
 * - the version is the snapshot's; tokens are non-empty, inside the text, sorted and non-overlapping;
 * - every non-whitespace character belongs to exactly one token;
 * - diagnostics are sorted with [Diagnostic.ORDER] and none is an internal error (the fail-safe guard never fired);
 * - KMeta/KModel: every ERROR token overlaps a LEXER diagnostic and vice versa; KStates reports no LEXER errors;
 * - KStates: `embeddedRegions` are exactly the `EvalBody.content` ranges of `EvalBodies.find`, and every embedded,
 *   PATH_REFERENCE and MACRO_REFERENCE token lies in one of them; KMeta/KModel have no embedded regions or tokens.
 */
internal fun assertTokenInvariants(language: KarpfenLanguage, text: String, result: TokenizeResult, label: String) {
    assertEquals(7L, result.version, "version of $label")
    val covered = IntArray(text.length)
    var previousEnd = 0
    for (token in result.tokens) {
        val range = token.range
        assertTrue(!range.isEmpty && range.end <= text.length, "token $token outside the text (${text.length}) in $label")
        assertTrue(range.start >= previousEnd, "token $token overlaps or precedes its predecessor in $label")
        previousEnd = range.end
        for (k in range.start until range.end) covered[k]++
    }
    for (k in text.indices) {
        if (!isGrammarWhitespace(text[k])) {
            assertEquals(1, covered[k], "character '${text[k]}' at $k is in ${covered[k]} tokens in $label")
        }
    }
    assertEquals(result.diagnostics.sortedWith(Diagnostic.ORDER), result.diagnostics, "diagnostic order in $label")
    assertTrue(
        result.diagnostics.none { it.code == DiagnosticCodes.INTERNAL_ERROR },
        "the fail-safe guard fired in $label: ${result.diagnostics}",
    )
    val lexerErrors = result.diagnostics.filter { it.source == DiagnosticSource.LEXER }
    if (language == KarpfenLanguage.KSTATES) {
        assertTrue(lexerErrors.isEmpty(), "the KStates lexer has a catch-all token, but reported $lexerErrors in $label")
        assertEmbeddedRegions(text, result, label)
    } else {
        assertTrue(result.embeddedRegions.isEmpty(), "embedded regions in $label")
        assertTrue(result.tokens.none { it.kind.isEmbedded }, "embedded tokens in $label")
        assertLexerErrorsMatchErrorTokens(result, lexerErrors, label)
    }
}

/** KMeta/KModel: lexer errors always become ERROR tokens plus LEXER diagnostics; each explains the other. */
private fun assertLexerErrorsMatchErrorTokens(result: TokenizeResult, lexerErrors: List<Diagnostic>, label: String) {
    val errorTokens = result.tokens.filter { it.kind == TokenKind.ERROR }
    for (token in errorTokens) {
        assertTrue(lexerErrors.any { it.range.overlaps(token.range) }, "error token $token without a lexer diagnostic in $label")
    }
    for (error in lexerErrors) {
        assertTrue(errorTokens.any { it.range.overlaps(error.range) }, "lexer diagnostic $error without an error token in $label")
    }
}

/**
 * KStates: the embedded regions are exactly the EVAL body contents of [EvalBodies.find], and every embedded token lies
 * in one of them.
 */
private fun assertEmbeddedRegions(text: String, result: TokenizeResult, label: String) {
    val lexed = KarpfenLexers.lex(KarpfenLanguage.KSTATES, text)
    val expected = EvalBodies.find(lexed.tokens, lexed.offsets).map { it.content }
    assertEquals(expected, result.embeddedRegions, "embedded regions of $label")
    for (token in result.tokens.filter { it.kind.isEmbedded }) {
        assertTrue(
            result.embeddedRegions.any { token.range in it },
            "embedded token $token lies in no embedded region ${result.embeddedRegions} in $label",
        )
    }
}
