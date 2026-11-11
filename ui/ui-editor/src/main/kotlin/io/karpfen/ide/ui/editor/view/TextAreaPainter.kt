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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.session.VersionedDiagnostics
import io.karpfen.ide.core.lang.HighlightSpan
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.caret.Caret
import io.karpfen.ide.ui.editor.model.find.FindResult
import io.karpfen.ide.ui.editor.state.BracketMatchSnapshot
import io.karpfen.ide.ui.editor.state.HighlightSnapshot
import io.karpfen.ide.ui.editor.theme.EditorTheme
import io.karpfen.ide.ui.editor.view.geometry.DisplayLine
import io.karpfen.ide.ui.editor.view.geometry.TextAreaFrame
import io.karpfen.ide.ui.editor.view.geometry.TextGeometry
import io.karpfen.ide.ui.editor.view.geometry.VersionedRanges
import io.karpfen.ide.ui.editor.view.render.Decorations.background
import io.karpfen.ide.ui.editor.view.render.Decorations.box
import io.karpfen.ide.ui.editor.view.render.Decorations.caret
import io.karpfen.ide.ui.editor.view.render.Decorations.dottedUnderline
import io.karpfen.ide.ui.editor.view.render.Decorations.wavyUnderline

/**
 * Everything one frame of the text area shows, as immutable inputs. These are the latest values of the flows of the
 * editor state. The painter maps ranges of older versions into the drawn version.
 *
 * @property text the edited text, for `EditableText.mapRange`, the single range-mapping rule.
 * @property carets the carets in offsets of the drawn snapshot.
 * @property find the find result while the find bar is open, or else null. Matches are drawn only while it is set.
 * @property caretVisible whether the caret is drawn in this frame. That requires focus and the visible blink phase.
 */
internal class TextAreaContent(
    val frame: TextAreaFrame,
    val text: EditableText,
    val highlights: HighlightSnapshot,
    val diagnostics: VersionedDiagnostics,
    val carets: List<Caret>,
    val bracket: BracketMatchSnapshot,
    val find: FindResult?,
    val caretVisible: Boolean,
    val theme: EditorTheme,
    val textStyle: TextStyle,
    val fontSize: Float,
)

/**
 * Paints the text area from a [TextAreaContent]. Painting is virtualized. Only the laid-out lines, which are the visible
 * lines plus overscan, are measured through the [LineLayouter] and its cache, and drawn.
 *
 * The painting order is the current line, the find matches, the selection, the matching brackets, the text, the
 * diagnostic underlines and the caret. Every range of an older version is mapped with `EditableText.mapRange`, the
 * single range-mapping rule. That applies to highlight spans, find matches, diagnostics and bracket matches. A range is
 * never mapped by mapping its two bounds separately. A range that does not map is not drawn.
 */
internal class TextAreaPainter(private val layouter: LineLayouter, private val metrics: PainterMetrics) {

    /**
     * The sizes of the decorations, in pixels. They depend on the device density.
     *
     * @property caretWidth the width of the caret bar.
     * @property stroke the width of underlines and bracket boxes.
     * @property waveAmplitude the amplitude of wavy underlines.
     */
    data class PainterMetrics(val caretWidth: Float, val stroke: Float, val waveAmplitude: Float)

    /** Draws [content] into this scope. The caller clips the scope to the text area. */
    fun DrawScope.paint(content: TextAreaContent) {
        val frame = content.frame
        val lines = frame.laidOut
        if (lines.isEmpty()) return
        val geometry = frame.geometry
        val window = TextRange(geometry.lineRange(lines.first).start, geometry.lineRange(lines.last).end)
        val theme = content.theme
        paintCurrentLine(content, lines)
        content.find?.let { paintFindMatches(content, it, window) }
        for (caret in content.carets) paintRange(frame, caret.selection, theme.selection)
        paintBrackets(content)
        paintText(content, window)
        paintDiagnostics(content, window)
        if (content.caretVisible) {
            for (caret in content.carets) {
                val rect = frame.caretRect(caret.position, metrics.caretWidth)
                caret(rect.center.x, rect.top, rect.height, metrics.caretWidth, theme.caret)
            }
        }
    }

    /** Paints the background of the primary caret's line, if that line is laid out. */
    private fun DrawScope.paintCurrentLine(content: TextAreaContent, lines: IntRange) {
        val frame = content.frame
        val line = frame.geometry.lineOf(content.carets.first().position)
        if (line !in lines) return
        val top = frame.toAreaY(frame.geometry.lineTop(line))
        background(Rect(0f, top, size.width, top + frame.geometry.lineHeight), content.theme.currentLine)
    }

    /**
     * Paints the find matches on the laid-out lines. Their segments are computed in one batch, with one pass per line
     * no matter how many matches it holds. Segments left or right of the text area are not drawn.
     */
    private fun DrawScope.paintFindMatches(content: TextAreaContent, find: FindResult, window: TextRange) {
        val frame = content.frame
        val map = mapper(content, find.version)
        val current = find.current?.let(map)
        val matches = VersionedRanges(find.matches, { it }, map).intersecting(window).map { it.range }
        frame.geometry.segments(matches, frame.laidOut).forEachIndexed { index, segments ->
            val color = if (matches[index] == current) content.theme.findMatchCurrent else content.theme.findMatch
            paintSegments(frame, segments, color)
        }
    }

    /** Fills the segments of [range] on the laid-out lines with [color]. */
    private fun DrawScope.paintRange(frame: TextAreaFrame, range: TextRange, color: Color) {
        paintSegments(frame, frame.geometry.segments(range, frame.laidOut), color)
    }

    /** Fills [segments] with [color]. Segments outside the width of the text area are skipped. */
    private fun DrawScope.paintSegments(frame: TextAreaFrame, segments: List<TextGeometry.Segment>, color: Color) {
        val geometry = frame.geometry
        for (segment in segments) {
            val left = frame.toAreaX(segment.left)
            val right = frame.toAreaX(segment.right)
            if (right < 0f || left > size.width) continue
            val top = frame.toAreaY(geometry.lineTop(segment.line))
            background(Rect(left, top, right, top + geometry.lineHeight), color)
        }
    }

    /** Paints boxes around both brackets of the matching pair, mapped into the drawn version. */
    private fun DrawScope.paintBrackets(content: TextAreaContent) {
        val match = content.bracket.match ?: return
        val map = mapper(content, content.bracket.version)
        listOfNotNull(map(match.open), map(match.close)).filterNot { it.isEmpty }.forEach { range ->
            content.frame.characterRect(range.start)?.let { box(it, content.theme.matchingBracket, metrics.stroke) }
        }
    }

    /**
     * Paints the laid-out lines with the highlight spans that intersect [window]. The spans are cut into spans per line,
     * in line columns, and the lines are laid out through the [LineLayouter].
     */
    private fun DrawScope.paintText(content: TextAreaContent, window: TextRange) {
        val frame = content.frame
        val geometry = frame.geometry
        val spans = VersionedRanges(content.highlights.spans, HighlightSpan::range, mapper(content, content.highlights.version))
            .intersecting(window)
        var next = 0
        for (line in frame.laidOut) {
            val lineRange = geometry.lineRange(line)
            val lineSpans = ArrayList<LineSpan>()
            while (next < spans.size && spans[next].range.end <= lineRange.start) next++
            var index = next
            while (index < spans.size && spans[index].range.start < lineRange.end) {
                val range = spans[index].range
                val start = maxOf(range.start, lineRange.start) - lineRange.start
                val end = minOf(range.end, lineRange.end) - lineRange.start
                if (start < end) lineSpans += LineSpan(start, end, spans[index].item.style)
                index++
            }
            val display = DisplayLine.of(geometry.snapshot.lineText(line), geometry.tabStops)
            val layout = layouter.layout(display, lineSpans, content.textStyle, content.theme, content.fontSize, geometry.tabStops.tabWidth)
            drawText(layout, topLeft = Offset(frame.toAreaX(geometry.textLeft), frame.toAreaY(geometry.lineTop(line))))
        }
    }

    /** Paints the underlines of the diagnostics in [window]. They are wavy for errors and warnings, and dotted for the others. */
    private fun DrawScope.paintDiagnostics(content: TextAreaContent, window: TextRange) {
        val frame = content.frame
        val geometry = frame.geometry
        val diagnostics = content.diagnostics
        for (diagnostic in diagnostics.diagnostics) {
            val range = content.text.mapRange(diagnostic.range, diagnostics.snapshot, geometry.snapshot.version) ?: continue
            if (range.end < window.start || range.start > window.end) continue
            val color = when (diagnostic.severity) {
                Severity.ERROR -> content.theme.error
                Severity.WARNING -> content.theme.warning
                Severity.INFO, Severity.HINT -> content.theme.info
            }
            for (segment in underlineSegments(frame, range)) {
                val y = frame.toAreaY(geometry.lineTop(segment.line) + geometry.lineHeight) - metrics.waveAmplitude - metrics.stroke
                val left = frame.toAreaX(segment.left)
                val right = frame.toAreaX(segment.right)
                if (diagnostic.severity == Severity.ERROR || diagnostic.severity == Severity.WARNING) {
                    wavyUnderline(left, right, y, metrics.waveAmplitude, color, metrics.stroke)
                } else {
                    dottedUnderline(left, right, y, metrics.stroke, color)
                }
            }
        }
    }

    /**
     * Returns the underlined pieces of [range]. An empty range is marked one cell wide. At the end of a line that cell
     * is the one before the range.
     */
    private fun underlineSegments(frame: TextAreaFrame, range: TextRange): List<TextGeometry.Segment> = frame.geometry.let { geometry ->
        if (!range.isEmpty) {
            geometry.segments(range, frame.laidOut)
        } else {
            val line = geometry.lineOf(range.start)
            if (line !in frame.laidOut) {
                emptyList()
            } else {
                val x = geometry.xOf(range.start)
                val atEnd = range.start == geometry.lineRange(line).end && x > geometry.textLeft
                val left = if (atEnd) x - geometry.charWidth else x
                listOf(TextGeometry.Segment(line, left, left + geometry.charWidth))
            }
        }
    }

    /** Returns a function that maps ranges of [version] into the drawn version. It is the identity when the versions agree. */
    private fun mapper(content: TextAreaContent, version: Long): (TextRange) -> TextRange? {
        val drawn = content.frame.geometry.snapshot.version
        if (version == drawn) return { it }
        val text = content.text
        return { range -> text.mapRange(range, version, text.lineage, drawn) }
    }
}
