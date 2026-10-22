package io.karpfen.ide.core.document.syntax

import io.karpfen.ide.core.text.TextSnapshot

/**
 * Splits a document into classified tokens. One implementation per language, reached through
 * [io.karpfen.ide.core.document.language.LanguageSupport.tokenizer].
 *
 * Contract (MUST):
 * - Never throws, for any input; lexer errors become [TokenKind.ERROR] tokens plus LEXER diagnostics.
 * - The result's version equals [TextSnapshot.version]; tokens are sorted, non-overlapping, and cover every
 *   non-whitespace character exactly once.
 * - Stateless and thread-safe: may be called concurrently for different snapshots.
 * - Budget: a 5,000-line KStates document tokenizes in < 30 ms. This is a target that a time-budget test documents;
 *   no test run executes it.
 */
public fun interface Tokenizer {
    /** Tokenizes [snapshot]. */
    public fun tokenize(snapshot: TextSnapshot): TokenizeResult
}
