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
    ;

    /**
     * True for the semantic styles, the ones that come from the parse outcome's semantic spans (one per `SemanticKind`:
     * type, primitive type, property, object, state, macro, event and binding names, event domains, model paths,
     * descriptions); false for the lexical styles from token kinds. The editor carries the semantic layer of an older
     * snapshot forward while the parse lags the tokens (see [overlayHighlights]).
     */
    public val isSemantic: Boolean
        get() = when (this) {
            TYPE_NAME, PRIMITIVE_TYPE, PROPERTY_NAME, OBJECT_NAME, STATE_NAME, MACRO_NAME, EVENT_NAME, EVENT_DOMAIN,
            MODEL_PATH, DESCRIPTION, BINDING_NAME,
            -> true
            else -> false
        }
}

/** A styled range (spec §5). */
public data class HighlightSpan(public val range: TextRange, public val style: HighlightStyle)

/**
 * Overlays [overlay] on [base] — the one overlay rule of highlighting (decision 39 of the work-package plan): the
 * highlight provider lays the semantic spans of a current parse over the lexical spans, and the editor lays the
 * previous snapshot's semantic spans (mapped forward) over fresh lexical spans while the parse lags. Inside every
 * overlay span its style wins; base spans partly covered are split and keep their style outside; overlay spans are kept
 * whole. Empty spans are dropped. Complete in the contracts step; never throws.
 *
 * Precondition: each input is sorted by start and non-overlapping (as highlight results and semantic spans are); the
 * result is then sorted and non-overlapping too.
 */
public fun overlayHighlights(base: List<HighlightSpan>, overlay: List<HighlightSpan>): List<HighlightSpan> {
    val covers = overlay.filterNot { it.range.isEmpty }
    if (covers.isEmpty()) return base.filterNot { it.range.isEmpty }
    val pieces = ArrayList<HighlightSpan>(base.size + covers.size)
    var first = 0
    for (span in base) {
        if (span.range.isEmpty) continue
        while (first < covers.size && covers[first].range.end <= span.range.start) first++
        var cursor = span.range.start
        var index = first
        while (index < covers.size && covers[index].range.start < span.range.end) {
            val cover = covers[index].range
            if (cover.start > cursor) pieces += HighlightSpan(TextRange(cursor, cover.start), span.style)
            cursor = maxOf(cursor, cover.end)
            index++
        }
        if (cursor < span.range.end) pieces += HighlightSpan(TextRange(cursor, span.range.end), span.style)
    }
    pieces += covers
    return pieces.sortedBy { it.range.start }
}
