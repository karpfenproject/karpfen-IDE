package io.karpfen.ide.core.lang

import io.karpfen.ide.core.text.TextRange

/**
 * Visual style classes for highlighting (spec §5). The editor maps each style to colours through its `EditorTheme`.
 * Lexical styles come from token kinds, semantic styles from the parse outcome's semantic spans.
 */
public enum class HighlightStyle {
    /** Grammar keyword. */
    KEYWORD,

    /** Action operation (`SET`, `APPEND`, …, `EVENT` as action). */
    OPERATION,

    /** String literal without a more specific semantic style. */
    STRING,

    /** Number literal. */
    NUMBER,

    /** `//` comment. */
    COMMENT,

    /** Braces, parentheses, commas, colons. */
    PUNCTUATION,

    /** `->` and other operators. */
    OPERATOR,

    /** Identifier. */
    IDENTIFIER,

    /** Type name. */
    TYPE_NAME,

    /** `number`, `string`, `boolean`. */
    PRIMITIVE_TYPE,

    /** Property or relation key. */
    PROPERTY_NAME,

    /** Object key. */
    OBJECT_NAME,

    /** State name. */
    STATE_NAME,

    /** Macro name. */
    MACRO_NAME,

    /** Event name. */
    EVENT_NAME,

    /** Event domain. */
    EVENT_DOMAIN,

    /** Model path. */
    MODEL_PATH,

    /** Type description. */
    DESCRIPTION,

    /** `WITH … AS` binding name. */
    BINDING_NAME,

    /** Embedded Python code. */
    EMBEDDED_CODE,

    /** Embedded Python keyword. */
    EMBEDDED_KEYWORD,

    /** Embedded Python `#` comment. */
    EMBEDDED_COMMENT,

    /** Embedded Python string. */
    EMBEDDED_STRING,

    /** Embedded Python number. */
    EMBEDDED_NUMBER,

    /** `$( … )` model-path reference in EVAL. */
    PATH_REFERENCE,

    /** `@name` macro reference in EVAL. */
    MACRO_REFERENCE,

    /** Unrecognized input. */
    ERROR,
}

/** A styled range (spec §5). */
public data class HighlightSpan(public val range: TextRange, public val style: HighlightStyle)
