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
 * The editor's decoration primitives (spec §7.2), drawn in text-area coordinates: filled backgrounds (current line,
 * selection, find matches), bracket boxes, the caret and the diagnostic underlines — wavy for errors and warnings,
 * dotted for information. Stateless drawing functions; what to draw where is decided by the caller.
 */
internal object Decorations {

    /** A filled background of [rect]. */
    fun DrawScope.background(rect: Rect, color: Color) {
        if (rect.width <= 0f || rect.height <= 0f) return
        drawRect(color, topLeft = rect.topLeft, size = rect.size)
    }

    /** The outline of [rect] (matching-bracket box). */
    fun DrawScope.box(rect: Rect, color: Color, strokeWidth: Float) {
        drawRect(color, topLeft = rect.topLeft, size = rect.size, style = Stroke(width = strokeWidth))
    }

    /** The caret: a bar of [width] at [x] from [top], [height] tall. */
    fun DrawScope.caret(x: Float, top: Float, height: Float, width: Float, color: Color) {
        drawRect(color, topLeft = Offset(x - width / 2f, top), size = Size(width, height))
    }

    /** A wavy underline from [left] to [right] around [y] (errors, warnings). */
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

    /** A dotted underline from [left] to [right] at [y] (information, hints). */
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
