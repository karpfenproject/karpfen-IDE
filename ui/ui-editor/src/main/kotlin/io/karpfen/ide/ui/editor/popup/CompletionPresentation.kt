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
 * The kind badge of a completion row: a short [text], an accessible [description], and the highlight [style] whose
 * colour the badge takes from the editor theme, so a badge matches how such names look in the text.
 */
internal data class KindBadge(val text: String, val description: String, val style: HighlightStyle) {

    /** The badge of each completion kind. */
    companion object {
        /** The badge of [kind] (exhaustive: a new kind needs a badge). */
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
            CompletionKind.PATH_SEGMENT -> KindBadge("→", "path segment", HighlightStyle.MODEL_PATH)
            CompletionKind.BINDING -> KindBadge("b", "binding", HighlightStyle.BINDING_NAME)
            CompletionKind.FILE -> KindBadge("f", "file", HighlightStyle.FILE_PATH)
            CompletionKind.VALUE -> KindBadge("v", "value", HighlightStyle.NUMBER)
        }
    }
}

/**
 * Text of a completion row (pure). The label's matched part is emphasized where it is known without repeating the
 * ranking rules: the filter text when the label starts with it (case-insensitively), which is how most items match
 * while typing an identifier. Word-boundary and subsequence matches are shown unemphasized, because the ranking of the
 * model does not expose its matched positions.
 */
internal object CompletionText {

    /** [label] with its prefix [filterText] (if it is one) in [emphasis]. */
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
 * Measures of the completion list in px (pure): rows of [rowHeight], monospace characters [charWidth] wide. The list
 * shows at most [CompletionUiState.MAX_VISIBLE_ROWS] rows (the model pages by as many) and is as wide as its longest
 * row (badge, label, detail), within [minWidth] and [maxWidth].
 */
internal data class CompletionListMetrics(
    val rowHeight: Int,
    val charWidth: Float,
    val minWidth: Int,
    val maxWidth: Int,
    /** Width of everything in a row besides label and detail characters (badge, paddings, gaps). */
    val rowChrome: Int,
) {

    /** The list's height for [itemCount] items: at most [CompletionUiState.MAX_VISIBLE_ROWS] rows. */
    fun height(itemCount: Int): Int = rowHeight * itemCount.coerceIn(0, CompletionUiState.MAX_VISIBLE_ROWS)

    /** The list's width for [items]: the longest label plus detail (with a gap of [DETAIL_GAP_CHARS]), clamped. */
    fun width(items: List<CompletionItem>): Int {
        val chars = items.maxOfOrNull { item -> item.label.length + (item.detail?.let { it.length + DETAIL_GAP_CHARS } ?: 0) } ?: 0
        return (rowChrome + chars * charWidth).toInt().coerceIn(minWidth, maxOf(minWidth, maxWidth))
    }

    /** The detail gap. */
    companion object {
        /** Characters of space between a label and its detail. */
        const val DETAIL_GAP_CHARS: Int = 3
    }
}
