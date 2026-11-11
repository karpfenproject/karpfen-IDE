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
package io.karpfen.ide.ui.editor.view

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import io.karpfen.ide.core.lang.HighlightStyle
import io.karpfen.ide.ui.editor.theme.EditorTheme
import io.karpfen.ide.ui.editor.view.geometry.DisplayLine

/** A styled piece of one line, in columns of the line's text. [startColumn] is less than [endColumn]. */
internal data class LineSpan(val startColumn: Int, val endColumn: Int, val style: HighlightStyle)

/**
 * Lays out single lines for drawing. It takes the display text of the line from [DisplayLine], with tabs expanded, and
 * applies the [EditorTheme] style of each [LineSpan]. The result is measured with [measurer] as one line without
 * wrapping and kept in [cache]. The cache key is the line text together with the spans and the font settings. So only
 * lines that changed or scrolled in are measured. A cached layout is used only if it was measured for exactly the same
 * styled text and style, so a hash collision of the spans leads to a new measurement.
 */
internal class LineLayouter(private val measurer: TextMeasurer, private val cache: LineLayoutCache) {

    /** Returns the layout of [line] with [spans] in [style]. [fontSize] is in sp, and [tabWidth] is the tab display width. */
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

    /**
     * Returns the display text of [line] with the theme's style of every span. The span columns are mapped to display
     * offsets.
     */
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

    /** Holds the constant of the key hashing. */
    private companion object {
        /** The multiplier that combines the hash of the spans with the identity of the theme. */
        const val HASH_FACTOR = 31
    }
}
