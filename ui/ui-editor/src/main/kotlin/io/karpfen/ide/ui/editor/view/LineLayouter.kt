package io.karpfen.ide.ui.editor.view

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import io.karpfen.ide.core.lang.HighlightStyle
import io.karpfen.ide.ui.editor.theme.EditorTheme
import io.karpfen.ide.ui.editor.view.geometry.DisplayLine

/** A styled piece of one line, in columns of the line's text (`startColumn < endColumn`). */
internal data class LineSpan(val startColumn: Int, val endColumn: Int, val style: HighlightStyle)

/**
 * Lays out single lines for drawing: the line's display text (tabs expanded, [DisplayLine]) with the
 * [EditorTheme] style of each [LineSpan], measured with [measurer] on one unwrapped line and kept in [cache] (keyed by
 * line text + spans + font settings), so only lines that changed or scrolled in are measured. A cached layout is used
 * only if it was measured for exactly the same styled text and style (a hash collision of the spans re-measures).
 */
internal class LineLayouter(private val measurer: TextMeasurer, private val cache: LineLayoutCache) {

    /** The layout of [line] with [spans] in [style] (font size [fontSize] sp, tab width [tabWidth]). */
    fun layout(
        line: DisplayLine,
        spans: List<LineSpan>,
        style: TextStyle,
        theme: EditorTheme,
        fontSize: Float,
        tabWidth: Int,
    ): TextLayoutResult {
        val styled = styledText(line, spans, theme)
        val key = LineLayoutKey(line.source, HASH_FACTOR * spans.hashCode() + System.identityHashCode(theme), fontSize, tabWidth)
        cache.get(key)?.let { cached ->
            if (cached.layoutInput.text == styled && cached.layoutInput.style == style) return cached
        }
        val measured = measurer.measure(styled, style, overflow = TextOverflow.Clip, softWrap = false, maxLines = 1)
        cache.put(key, measured)
        return measured
    }

    /** The display text of [line] with the theme's style of every span, span columns mapped to display offsets. */
    private fun styledText(line: DisplayLine, spans: List<LineSpan>, theme: EditorTheme): AnnotatedString {
        if (spans.isEmpty()) return AnnotatedString(line.text)
        val builder = AnnotatedString.Builder(line.text)
        for (span in spans) {
            val spanStyle = theme.styles[span.style] ?: continue
            val start = line.displayOffsetOf(span.startColumn)
            val end = line.displayOffsetOf(span.endColumn)
            if (start < end) builder.addStyle(spanStyle, start, end)
        }
        return builder.toAnnotatedString()
    }

    /** The key hashing. */
    private companion object {
        /** Multiplier combining the spans' hash with the theme's identity. */
        const val HASH_FACTOR = 31
    }
}
