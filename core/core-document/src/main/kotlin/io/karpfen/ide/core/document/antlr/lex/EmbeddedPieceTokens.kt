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

import io.karpfen.ide.core.document.antlr.EmbeddedKind
import io.karpfen.ide.core.document.syntax.TokenKind

/**
 * How the pieces of an EVAL body, as found by `EvalBodies.scan`, become tokens. The function is pure and works over the
 * closed [EmbeddedKind] vocabulary. The pieces cause no diagnostics: dsl-tools reports a `//` inside EVAL code itself,
 * as the warning `kstates.comment-in-eval` of the KStates reader. The object holds no state.
 */
internal object EmbeddedPieceTokens {

    /**
     * The token kind of a piece of [kind]. A `//` comment is a karpfen [TokenKind.COMMENT]. Every other piece gets one
     * of the embedded kinds.
     */
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
}
