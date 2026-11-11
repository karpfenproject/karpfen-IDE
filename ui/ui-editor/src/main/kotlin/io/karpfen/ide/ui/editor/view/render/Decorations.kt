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
package io.karpfen.ide.ui.editor.view.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The decoration primitives of the editor, drawn in text area coordinates. They draw filled backgrounds for the
 * current line, the selection and find matches. They also draw bracket boxes, the caret and the diagnostic underlines.
 * Underlines are wavy for errors and warnings, and dotted for information. These are drawing functions without state.
 * The caller decides what to draw where.
 */
internal object Decorations {

    /** Draws a filled background of [rect]. Empty rectangles are skipped. */
    fun DrawScope.background(rect: Rect, color: Color) {
        if (rect.width <= 0f || rect.height <= 0f) return
        drawRect(color, topLeft = rect.topLeft, size = rect.size)
    }

    /** Draws the outline of [rect]. The matching bracket box uses it. */
    fun DrawScope.box(rect: Rect, color: Color, strokeWidth: Float) {
        drawRect(color, topLeft = rect.topLeft, size = rect.size, style = Stroke(width = strokeWidth))
    }

    /** Draws the caret as a bar of [width], centred at [x], starting at [top] and [height] tall. */
    fun DrawScope.caret(x: Float, top: Float, height: Float, width: Float, color: Color) {
        drawRect(color, topLeft = Offset(x - width / 2f, top), size = Size(width, height))
    }

    /** Draws a wavy underline from [left] to [right] around [y]. Errors and warnings use it. */
    fun DrawScope.wavyUnderline(left: Float, right: Float, y: Float, amplitude: Float, color: Color, strokeWidth: Float) {
        if (right <= left) return
        val halfWave = amplitude * 2f
        val path = Path()
        path.moveTo(left, y)
        var x = left
        var up = true
        while (x < right) {
            val next = minOf(x + halfWave, right)
            path.lineTo(next, if (up) y - amplitude else y + amplitude)
            up = !up
            x = next
        }
        drawPath(path, color, style = Stroke(width = strokeWidth))
    }

    /** Draws a dotted underline from [left] to [right] at [y]. Information and hints use it. */
    fun DrawScope.dottedUnderline(left: Float, right: Float, y: Float, dot: Float, color: Color) {
        if (right <= left) return
        drawLine(
            color = color,
            start = Offset(left, y),
            end = Offset(right, y),
            strokeWidth = dot,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dot, dot)),
        )
    }
}
