package io.karpfen.ide.ui.editor.popup

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.HighlightStyle

/**
 * The kind badge of a completion row (spec §7.2): a short [text], an accessible [description], and the highlight
 * [style] whose colour the badge takes from the editor theme, so a badge matches how such names look in the text.
 */
internal data class KindBadge(val text: String, val description: String, val style: HighlightStyle) {

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
 * shows at most [MAX_VISIBLE_ROWS] rows (spec §7.2) and is as wide as its longest row (badge, label, detail), within
 * [minWidth] and [maxWidth].
 */
internal data class CompletionListMetrics(
    val rowHeight: Int,
    val charWidth: Float,
    val minWidth: Int,
    val maxWidth: Int,
    /** Width of everything in a row besides label and detail characters (badge, paddings, gaps). */
    val rowChrome: Int,
) {

    /** The list's height for [itemCount] items: at most [MAX_VISIBLE_ROWS] rows. */
    fun height(itemCount: Int): Int = rowHeight * itemCount.coerceIn(0, MAX_VISIBLE_ROWS)

    /** The list's width for [items]: the longest label plus detail (with a gap of [DETAIL_GAP_CHARS]), clamped. */
    fun width(items: List<CompletionItem>): Int {
        val chars = items.maxOfOrNull { item -> item.label.length + (item.detail?.let { it.length + DETAIL_GAP_CHARS } ?: 0) } ?: 0
        return (rowChrome + chars * charWidth).toInt().coerceIn(minWidth, maxOf(minWidth, maxWidth))
    }

    companion object {
        /** The most rows the list shows at once (spec §7.2). */
        const val MAX_VISIBLE_ROWS: Int = 12

        /** Characters of space between a label and its detail. */
        const val DETAIL_GAP_CHARS: Int = 3
    }
}
