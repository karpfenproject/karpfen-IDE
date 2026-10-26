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
