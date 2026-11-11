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

import io.karpfen.ide.core.text.TextRange

/** The lexical classes of JSON as RFC 8259 defines them, plus [INVALID] for input that is no JSON token. */
public enum class JsonTokenKind {
    /** `{`. */
    BEGIN_OBJECT,

    /** `}`. */
    END_OBJECT,

    /** `[`. */
    BEGIN_ARRAY,

    /** `]`. */
    END_ARRAY,

    /** `:` between a key and its value. */
    NAME_SEPARATOR,

    /** `,` between members or items. */
    VALUE_SEPARATOR,

    /** A string with its quotes. An unterminated string ends before the line break or at the end of the text. */
    STRING,

    /** A number in JSON syntax, such as `-12`, `0.5` or `1e3`. */
    NUMBER,

    /** `true`. */
    TRUE,

    /** `false`. */
    FALSE,

    /** `null`. */
    NULL,

    /**
     * Anything else, such as a bare word, a malformed number or characters that JSON does not know. Always a LEXER
     * error.
     */
    INVALID,
    ;

    /** True for the tokens a value can start with. */
    public val isValueStart: Boolean
        get() = this == BEGIN_OBJECT || this == BEGIN_ARRAY || this == STRING || this == NUMBER || isLiteral

    /** True for `{` and `[`, the tokens a container starts with. */
    public val opensContainer: Boolean get() = this == BEGIN_OBJECT || this == BEGIN_ARRAY

    /** True for `true`, `false` and `null`. */
    public val isLiteral: Boolean get() = this == TRUE || this == FALSE || this == NULL

    /** True for the brackets and separators. */
    public val isStructural: Boolean
        get() = this == BEGIN_OBJECT || this == END_OBJECT || this == BEGIN_ARRAY || this == END_ARRAY ||
            this == NAME_SEPARATOR || this == VALUE_SEPARATOR
}

/**
 * One token of a JSON text.
 *
 * @property kind its lexical class.
 * @property range the token in the text, never empty.
 */
public data class JsonToken(public val kind: JsonTokenKind, public val range: TextRange) {
    init {
        require(!range.isEmpty) { "tokens are never empty, was $range" }
    }
}

/** True if the token at [at] is a string followed by `:`, so it is the key that starts a member. */
internal fun List<JsonToken>.startsMemberAt(at: Int): Boolean =
    getOrNull(at)?.kind == JsonTokenKind.STRING && getOrNull(at + 1)?.kind == JsonTokenKind.NAME_SEPARATOR
