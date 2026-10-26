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
package io.karpfen.ide.core.document.antlr

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.Lexer
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token

/**
 * The IDE's ANTLR error listener: collects lexer and parser errors as [Diagnostic]s instead of printing them to the
 * console, which is what ANTLR's default `ConsoleErrorListener` does. Parsing never throws; ANTLR's default recovery
 * goes on, and the errors it reports end up here.
 *
 * - Lexer errors (unrecognized input) become ERROR [DiagnosticCodes.LEXER_ERROR] diagnostics with source
 *   [DiagnosticSource.LEXER], covering the characters the lexer could not match (at least one character).
 * - Parser errors become ERROR [DiagnosticCodes.SYNTAX_ERROR] diagnostics with source [DiagnosticSource.PARSER],
 *   covering the offending token (an empty range at the end of the text for `<EOF>`).
 *
 * Ranges are converted with [offsets] (code points to UTF-16). One instance per lexing or parsing run; not thread-safe.
 * The tokenizers in `antlr.lex` and the tree parser in `antlr.tree` both use it.
 */
internal class IdeErrorListener(private val offsets: CodePointOffsets) : BaseErrorListener() {

    private val collected = ArrayList<Diagnostic>()

    /** Length of the text in UTF-16 code units. */
    private val textLength: Int get() = offsets.toUtf16(offsets.codePointCount)

    /** Everything reported so far, sorted with [Diagnostic.ORDER] and without duplicates. */
    val diagnostics: List<Diagnostic> get() = collected.distinct().sortedWith(Diagnostic.ORDER)

    /** Replaces every listener of [recognizer] (lexer or parser) by this one; returns [recognizer]. */
    fun <R : Recognizer<*, *>> attachTo(recognizer: R): R {
        recognizer.removeErrorListeners()
        recognizer.addErrorListener(this)
        return recognizer
    }

    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String?,
        e: RecognitionException?,
    ) {
        val message = msg ?: "syntax error"
        collected += if (recognizer is Lexer) {
            Diagnostic(lexerRange(recognizer), Severity.ERROR, message, DiagnosticCodes.LEXER_ERROR, DiagnosticSource.LEXER)
        } else {
            Diagnostic(tokenRange(offendingSymbol as? Token), Severity.ERROR, message, DiagnosticCodes.SYNTAX_ERROR, DiagnosticSource.PARSER)
        }
    }

    /**
     * When the lexer reports an error, the unmatched input runs from the start of the attempted token to the current
     * index inclusive (ANTLR's `Lexer.notifyListeners`); at the end of the input that is the empty end of the text.
     */
    private fun lexerRange(lexer: Lexer): TextRange {
        val start = lexer._tokenStartCharIndex
        val stop = maxOf(start, lexer.inputStream.index())
        return offsets.range(start, stop) ?: TextRange.empty(textLength)
    }

    /** The range of the offending [token]; the empty range at the end of the text for EOF or an unknown token. */
    private fun tokenRange(token: Token?): TextRange {
        if (token == null) return TextRange.empty(textLength)
        if (token.type == Token.EOF) return TextRange.empty(textLength)
        return offsets.range(token.startIndex, token.stopIndex) ?: TextRange.empty(textLength)
    }
}
