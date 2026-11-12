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
package io.karpfen.ide.ui.editor.popup

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.HighlightStyle
import io.karpfen.ide.ui.editor.model.completion.CompletionUiState

/**
 * The kind badge of a completion row. It has a short [text], an accessible [description] and a highlight [style]. The
 * badge takes the colour of [style] from the editor theme, so a badge matches how such names look in the text.
 */
internal data class KindBadge(val text: String, val description: String, val style: HighlightStyle) {

    /** The badge of each completion kind. */
    companion object {
        /** Returns the badge of [kind]. The `when` is exhaustive, so a new kind fails to compile until it has a badge. */
        fun of(kind: CompletionKind): KindBadge = when (kind) {
            CompletionKind.KEYWORD -> KindBadge("kw", "keyword", HighlightStyle.KEYWORD)
            CompletionKind.SNIPPET -> KindBadge("sn", "snippet", HighlightStyle.KEYWORD)
            CompletionKind.TYPE -> KindBadge("T", "type", HighlightStyle.TYPE_NAME)
            CompletionKind.PRIMITIVE_TYPE -> KindBadge("t", "primitive type", HighlightStyle.PRIMITIVE_TYPE)
            CompletionKind.PROPERTY -> KindBadge("p", "property", HighlightStyle.PROPERTY_NAME)
            CompletionKind.RELATION -> KindBadge("r", "relation", HighlightStyle.PROPERTY_NAME)
            CompletionKind.OBJECT -> KindBadge("o", "object", HighlightStyle.OBJECT_NAME)
            CompletionKind.STATE -> KindBadge("S", "state", HighlightStyle.STATE_NAME)
            CompletionKind.MACRO -> KindBadge("m", "macro", HighlightStyle.MACRO_NAME)
            CompletionKind.EVENT -> KindBadge("e", "event", HighlightStyle.EVENT_NAME)
            CompletionKind.PATH_SEGMENT -> KindBadge("->", "path segment", HighlightStyle.MODEL_PATH)
            CompletionKind.BINDING -> KindBadge("b", "binding", HighlightStyle.BINDING_NAME)
            CompletionKind.FILE -> KindBadge("f", "file", HighlightStyle.FILE_PATH)
            CompletionKind.VALUE -> KindBadge("v", "value", HighlightStyle.NUMBER)
        }
    }
}

/**
 * The text of a completion row. The object holds pure functions. The matched part of the label is emphasized where it
 * is known without repeating the ranking rules. That is the case when the label starts with the filter text, ignoring
 * case, which is how most items match while the user types an identifier. Word boundary and subsequence matches are
 * shown without emphasis, because the ranking of the model does not expose the matched positions.
 */
internal object CompletionText {

    /** Returns [label] with its prefix [filterText] in [emphasis], if the label starts with it. */
    fun label(label: String, filterText: String, emphasis: SpanStyle): AnnotatedString = buildAnnotatedString {
        if (filterText.isNotEmpty() && label.startsWith(filterText, ignoreCase = true)) {
            withStyle(emphasis) { append(label.substring(0, filterText.length)) }
            append(label.substring(filterText.length))
        } else {
            append(label)
        }
    }
}

/**
 * The measures of the completion list in pixels. It is a plain value. Rows are [rowHeight] high, and the monospace
 * characters are [charWidth] wide. The list shows at most [CompletionUiState.MAX_VISIBLE_ROWS] rows, which is also the
 * number of rows the model pages by. It is as wide as its longest row with badge, label and detail, within [minWidth]
 * and [maxWidth].
 */
internal data class CompletionListMetrics(
    val rowHeight: Int,
    val charWidth: Float,
    val minWidth: Int,
    val maxWidth: Int,
    /** The width of everything in a row except the label and detail characters, such as the badge, paddings and gaps. */
    val rowChrome: Int,
) {

    /** Returns the height of the list for [itemCount] items. It shows at most [CompletionUiState.MAX_VISIBLE_ROWS] rows. */
    fun height(itemCount: Int): Int = rowHeight * itemCount.coerceIn(0, CompletionUiState.MAX_VISIBLE_ROWS)

    /**
     * Returns the width of the list for [items]. It fits the longest label plus detail, with a gap of [DETAIL_GAP_CHARS]
     * between them, and is clamped to [minWidth] and [maxWidth].
     */
    fun width(items: List<CompletionItem>): Int {
        val chars = items.maxOfOrNull { item -> item.label.length + (item.detail?.let { it.length + DETAIL_GAP_CHARS } ?: 0) } ?: 0
        return (rowChrome + chars * charWidth).toInt().coerceIn(minWidth, maxOf(minWidth, maxWidth))
    }

    /** Holds the detail gap. */
    companion object {
        /** The number of characters of space between a label and its detail. */
        const val DETAIL_GAP_CHARS: Int = 3
    }
}
