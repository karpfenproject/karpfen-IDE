package io.karpfen.ide.core.document.syntax

/**
 * Lexical classification of a [SyntaxToken] (spec §3.1). The `EMBEDDED_*`, [PATH_REFERENCE] and [MACRO_REFERENCE]
 * kinds only occur inside EVAL bodies (embedded Python).
 */
public enum class TokenKind {
    /** Grammar keywords other than operations (`type`, `STATE`, `TRANSITION`, `EVAL`, `EVENT` in conditions, …). */
    KEYWORD,

    /** Action operations `SET APPEND SETLIST DROPLIST SETOBJ APPENDOBJ DROPOBJ` (both cases) and `EVENT` as an action. */
    OPERATION,

    /** A quoted string literal of the karpfen grammar, including its quotes. */
    STRING,

    /** A number literal of the karpfen grammar. */
    NUMBER,

    /** An identifier of the karpfen grammar (e.g. `list` in a KStates type expression). */
    IDENTIFIER,

    /** Braces, parentheses, commas, colons. */
    PUNCTUATION,

    /** `->` and other operators. */
    OPERATOR,

    /** A `//` line comment (also inside EVAL bodies, where it is a karpfen comment, spec §3.1). */
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

    /** A `$( … )` model-path reference inside an EVAL body, including `$(` and `)`. */
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
