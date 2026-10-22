package io.karpfen.ide.core.document.json

import io.karpfen.ide.core.text.TextRange

/** The lexical classes of JSON (RFC 8259), plus [INVALID] for input that is no JSON token. */
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

    /** A string with its quotes; an unterminated string ends before the line break or at the end of the text. */
    STRING,

    /** A number in JSON syntax (`-12`, `0.5`, `1e3`). */
    NUMBER,

    /** `true`. */
    TRUE,

    /** `false`. */
    FALSE,

    /** `null`. */
    NULL,

    /** Anything else: a bare word, a malformed number or characters JSON does not know. Always a LEXER error. */
    INVALID,
    ;

    /** True for the tokens a value can start with. */
    public val isValueStart: Boolean
        get() = this == BEGIN_OBJECT || this == BEGIN_ARRAY || this == STRING || this == NUMBER || isLiteral

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
