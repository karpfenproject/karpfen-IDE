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
package io.karpfen.ide.core.document.syntax

/**
 * Lexical classification of a [SyntaxToken]. The `EMBEDDED_*`, [PATH_REFERENCE] and [MACRO_REFERENCE] kinds only occur
 * inside EVAL bodies, which hold embedded Python.
 */
public enum class TokenKind {
    /** Grammar keywords other than operations, such as `type`, `STATE`, `EVAL` and `EVENT` inside conditions. */
    KEYWORD,

    /**
     * The action operations `SET`, `APPEND`, `SETLIST`, `DROPLIST`, `SETOBJ`, `APPENDOBJ` and `DROPOBJ` in either case,
     * and `EVENT` as an action.
     */
    OPERATION,

    /** A quoted string literal of the karpfen grammar, including its quotes. */
    STRING,

    /** A number literal of the karpfen grammar. */
    NUMBER,

    /** An identifier of the karpfen grammar, such as `list` in a KStates type expression. */
    IDENTIFIER,

    /** Braces, parentheses, commas and colons. */
    PUNCTUATION,

    /** `->` and other operators. */
    OPERATOR,

    /** A `//` line comment, also inside EVAL bodies, where it is a karpfen comment that the Python code never sees. */
    COMMENT,

    /** Python code inside an EVAL body that has no more specific kind. */
    EMBEDDED_CODE,

    /** A Python keyword inside an EVAL body. */
    EMBEDDED_KEYWORD,

    /** A `#` comment inside an EVAL body. */
    EMBEDDED_COMMENT,

    /** A quoted string inside an EVAL body. */
    EMBEDDED_STRING,

    /** A number inside an EVAL body. */
    EMBEDDED_NUMBER,

    /** A model-path reference such as `$(position)` inside an EVAL body, including `$(` and `)`. */
    PATH_REFERENCE,

    /** An `@name` macro reference inside an EVAL body, including the `@`. */
    MACRO_REFERENCE,

    /** Input the lexer could not recognize. */
    ERROR,
    ;

    /** True for the kinds that only occur inside EVAL bodies. */
    public val isEmbedded: Boolean
        get() = this == EMBEDDED_CODE || this == EMBEDDED_KEYWORD || this == EMBEDDED_COMMENT ||
            this == EMBEDDED_STRING || this == EMBEDDED_NUMBER || this == PATH_REFERENCE || this == MACRO_REFERENCE
}
