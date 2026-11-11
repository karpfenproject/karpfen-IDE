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
package io.karpfen.ide.core.lang

import io.karpfen.ide.core.text.TextRange

/**
 * Visual style classes for highlighting. The editor maps each style to colours through its `EditorTheme`. Lexical
 * styles come from token kinds. Semantic styles come from the semantic spans of the parse outcome.
 */
public enum class HighlightStyle {
    /** Grammar keyword. */
    KEYWORD,

    /** Action operation, such as `SET`, `APPEND` or `EVENT` used as an action. */
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

    /** Property or relation key, or a key of an environment descriptor. */
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

    /** `WITH ... AS` binding name. */
    BINDING_NAME,

    /** File path in an environment descriptor. */
    FILE_PATH,

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

    /** `$( ... )` model-path reference in EVAL. */
    PATH_REFERENCE,

    /** `@name` macro reference in EVAL. */
    MACRO_REFERENCE,

    /** Unrecognized input. */
    ERROR,
    ;

    /**
     * True for the semantic styles, which come from the semantic spans of the parse outcome. There is one per
     * `SemanticKind`: type, primitive type, property, object, state, macro, event and binding names, event domains,
     * model paths, descriptions, and the file paths of environment descriptors. False for the lexical styles, which
     * come from token kinds. The editor carries the semantic layer of an older snapshot forward while the parse lags
     * behind the tokens, as described on [overlayHighlights].
     */
    public val isSemantic: Boolean
        get() = when (this) {
            TYPE_NAME, PRIMITIVE_TYPE, PROPERTY_NAME, OBJECT_NAME, STATE_NAME, MACRO_NAME, EVENT_NAME, EVENT_DOMAIN,
            MODEL_PATH, DESCRIPTION, BINDING_NAME, FILE_PATH,
            -> true
            else -> false
        }
}

/** A styled range of a document. */
public data class HighlightSpan(public val range: TextRange, public val style: HighlightStyle)

/**
 * Overlays [overlay] on [base]. This is the single overlay rule of highlighting. The highlight provider lays the
 * semantic spans of a current parse over the lexical spans. While the parse lags behind, the editor lays the semantic
 * spans of the previous snapshot, mapped forward, over fresh lexical spans. So semantic highlighting does not flicker
 * while the user types.
 *
 * Inside every overlay span its style wins. Base spans that are partly covered are split and keep their style outside
 * the cover. Overlay spans are kept whole. Empty spans are dropped. It never throws.
 *
 * Each input must be sorted by start and free of overlaps, as highlight results and semantic spans are. The result is
 * then sorted and free of overlaps too.
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
