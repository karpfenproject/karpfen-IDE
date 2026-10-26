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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.session.VersionedDiagnostics
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.ui.editor.theme.EditorTheme
import io.karpfen.ide.ui.editor.view.geometry.TextAreaFrame
import io.karpfen.ide.ui.editor.view.render.Decorations.background

/**
 * Paints the gutter next to the laid-out lines of a [TextAreaFrame]: one-based line numbers (right-aligned, the caret's
 * line emphasized), the current line's background and a diagnostic marker per line in the colour of the most severe
 * diagnostic starting on that line (ranges mapped into the drawn version with `EditableText.mapRange`). Number layouts
 * are cached in a [LineLayoutCache] of their own.
 */
internal class GutterPainter(private val measurer: TextMeasurer, private val numbers: LineLayoutCache) {

    /** Everything one frame of the gutter shows. */
    class Content(
        val frame: TextAreaFrame,
        val text: EditableText,
        val diagnostics: VersionedDiagnostics,
        val caretLine: Int,
        val showLineNumbers: Boolean,
        val theme: EditorTheme,
        val textStyle: TextStyle,
        val fontSize: Float,
    )

    /** Draws [content] into this scope, which is the gutter's area. */
    fun DrawScope.paint(content: Content) {
        val frame = content.frame
        val geometry = frame.geometry
        val theme = content.theme
        if (content.caretLine in frame.laidOut) {
            val top = frame.toAreaY(geometry.lineTop(content.caretLine))
            background(Rect(0f, top, size.width, top + geometry.lineHeight), theme.currentLine)
        }
        val severities = severityByLine(content)
        val markerRadius = geometry.charWidth * MARKER_RADIUS_CELLS
        for (line in frame.laidOut) {
            val top = frame.toAreaY(geometry.lineTop(line))
            severities[line]?.let { severity ->
                val color = when (severity) {
                    Severity.ERROR -> theme.error
                    Severity.WARNING -> theme.warning
                    Severity.INFO, Severity.HINT -> theme.info
                }
                drawCircle(color, markerRadius, Offset(geometry.charWidth, top + geometry.lineHeight / 2f))
            }
            if (content.showLineNumbers) {
                val color = if (line == content.caretLine) theme.foreground else theme.gutterForeground
                val style = content.textStyle.copy(color = color)
                val label = (line + 1).toString()
                val key = LineLayoutKey(label, color.hashCode(), content.fontSize, 0)
                val layout = numbers.get(key)?.takeIf { it.layoutInput.style == style }
                    ?: measurer.measure(label, style, softWrap = false, maxLines = 1).also { numbers.put(key, it) }
                val x = size.width - geometry.charWidth * RIGHT_PADDING_CELLS - layout.size.width
                drawText(layout, topLeft = Offset(x, top))
            }
        }
    }

    /**
     * The most severe diagnostic per laid-out line, by the line where its range starts (ERROR before WARNING before INFO
     * before HINT).
     */
    private fun severityByLine(content: Content): Map<Int, Severity> {
        val frame = content.frame
        val geometry = frame.geometry
        val result = HashMap<Int, Severity>()
        for (diagnostic in content.diagnostics.diagnostics) {
            val range = content.text.mapRange(diagnostic.range, content.diagnostics.snapshot, geometry.snapshot.version) ?: continue
            val line = geometry.lineOf(range.start)
            if (line !in frame.laidOut) continue
            val shown = result[line]
            if (shown == null || diagnostic.severity.ordinal < shown.ordinal) result[line] = diagnostic.severity
        }
        return result
    }

    /** The gutter's measures in cells. */
    companion object {
        /** Cells of the gutter besides the digits: the marker column and padding on both sides. */
        const val EXTRA_CELLS: Int = 3

        /** Space right of the line numbers. */
        private const val RIGHT_PADDING_CELLS = 0.8f

        /** Radius of a diagnostic marker. */
        private const val MARKER_RADIUS_CELLS = 0.3f
    }
}
