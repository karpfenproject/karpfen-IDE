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
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.antlr.EmbeddedKind
import io.karpfen.ide.core.document.antlr.EmbeddedPiece
import io.karpfen.ide.core.document.syntax.TokenKind

/**
 * How the pieces of an EVAL body (`EvalBodies.scan`) become tokens and diagnostics. Pure functions over the closed
 * [EmbeddedKind] vocabulary; no state.
 */
internal object EmbeddedPieceTokens {

    /**
     * The message of the `eval-double-slash` warning. It names the consequence (the rest of the line never reaches
     * Python) and the usual cause, Python's floor division, with replacements that the karpfen lexer leaves alone.
     */
    const val EVAL_DOUBLE_SLASH_MESSAGE: String =
        "`//` inside EVAL starts a karpfen comment; the rest of the line is dropped. " +
            "For Python floor division use int(a / b) or math.floor(a / b)."

    /** The token kind of a piece of [kind]: a `//` comment is a karpfen [TokenKind.COMMENT], the rest embedded kinds. */
    fun kindOf(kind: EmbeddedKind): TokenKind = when (kind) {
        EmbeddedKind.CODE -> TokenKind.EMBEDDED_CODE
        EmbeddedKind.KEYWORD -> TokenKind.EMBEDDED_KEYWORD
        EmbeddedKind.STRING -> TokenKind.EMBEDDED_STRING
        EmbeddedKind.NUMBER -> TokenKind.EMBEDDED_NUMBER
        EmbeddedKind.COMMENT -> TokenKind.EMBEDDED_COMMENT
        EmbeddedKind.KARPFEN_COMMENT -> TokenKind.COMMENT
        EmbeddedKind.PATH_REFERENCE -> TokenKind.PATH_REFERENCE
        EmbeddedKind.MACRO_REFERENCE -> TokenKind.MACRO_REFERENCE
    }

    /**
     * The diagnostic a piece causes: a `//` comment ([EmbeddedKind.KARPFEN_COMMENT]) is an `eval-double-slash` WARNING
     * (source IDE) over the comment, i.e. over everything the karpfen lexer drops from the Python code; other pieces
     * cause none.
     */
    fun diagnosticOf(piece: EmbeddedPiece): Diagnostic? =
        if (piece.kind != EmbeddedKind.KARPFEN_COMMENT || piece.range.isEmpty) {
            null
        } else {
            Diagnostic(piece.range, Severity.WARNING, EVAL_DOUBLE_SLASH_MESSAGE, DiagnosticCodes.EVAL_DOUBLE_SLASH, DiagnosticSource.IDE)
        }
}
