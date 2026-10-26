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
package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.syntax.SyntaxToken
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import org.slf4j.LoggerFactory

/**
 * Decorator that makes a [Tokenizer] keep its "never throws" contract (highlighting must work on every text, and no
 * exception from analysis may reach the editor): an exception of [delegate] is logged and turned into a degraded result
 * of one ERROR token per run of non-whitespace characters (so the token invariants still hold) and one ERROR
 * [DiagnosticCodes.INTERNAL_ERROR] diagnostic (source IDE) at the start of the text. The robustness tests assert that
 * this diagnostic never occurs, so a defect is still found by the tests rather than hidden by this guard.
 *
 * Stateless and thread-safe if [delegate] is.
 */
internal class FailSafeTokenizer(
    private val language: KarpfenLanguage,
    private val delegate: Tokenizer,
) : Tokenizer {

    override fun tokenize(snapshot: TextSnapshot): TokenizeResult =
        try {
            delegate.tokenize(snapshot)
        } catch (failure: Exception) {
            logger.error("Tokenizing {} text version {} failed", language.displayName, snapshot.version, failure)
            degraded(snapshot, failure)
        }

    /** One ERROR token per run of non-whitespace characters plus the internal-error diagnostic for [failure]. */
    private fun degraded(snapshot: TextSnapshot, failure: Exception): TokenizeResult {
        val text = snapshot.text
        val tokens = ArrayList<SyntaxToken>()
        var index = 0
        while (index < text.length) {
            if (isWhitespace(text[index])) {
                index++
                continue
            }
            val start = index
            while (index < text.length && !isWhitespace(text[index])) index++
            tokens += SyntaxToken(TextRange(start, index), TokenKind.ERROR)
        }
        val diagnostic = Diagnostic(
            range = TextRange.EMPTY,
            severity = Severity.ERROR,
            message = "Internal error while tokenizing ${language.displayName}: ${failure.javaClass.simpleName}: ${failure.message}",
            code = DiagnosticCodes.INTERNAL_ERROR,
            source = DiagnosticSource.IDE,
        )
        return TokenizeResult(snapshot.version, tokens, listOf(diagnostic))
    }

    /** The whitespace of the karpfen grammars (`[ \t\r\n]`). */
    private fun isWhitespace(char: Char): Boolean = char == ' ' || char == '\t' || char == '\r' || char == '\n'

    private companion object {
        private val logger = LoggerFactory.getLogger(FailSafeTokenizer::class.java)
    }
}
