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
package io.karpfen.ide.ui.foundation.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Drawing primitives of the karpfen icons on a 16 × 16 viewport (16 dp by default): filled shapes and round-capped
 * strokes. Pure functions over `ImageVector.Builder`; the icons themselves are composed in [IconShapes].
 */
internal object IconDrawing {

    /** Side length of the viewport and default size in dp. */
    const val SIZE: Float = 16f

    /** Fill of glyph icons that are meant to be tinted (Material convention). */
    val TINTABLE: Color = Color.Black

    /** Builds a 16 × 16 icon named [name]. */
    fun icon(name: String, draw: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = SIZE.dp,
            defaultHeight = SIZE.dp,
            viewportWidth = SIZE,
            viewportHeight = SIZE,
        ).apply(draw).build()

    /** A filled shape. */
    fun ImageVector.Builder.filled(color: Color, outline: PathBuilder.() -> Unit) {
        path(fill = SolidColor(color), pathBuilder = outline)
    }

    /** A round-capped, round-joined stroke of [width]. */
    fun ImageVector.Builder.stroked(color: Color, width: Float = 1.5f, line: PathBuilder.() -> Unit) {
        path(
            stroke = SolidColor(color),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = line,
        )
    }

    /** A polyline through [points] (x, y pairs). */
    fun PathBuilder.polyline(vararg points: Float) {
        require(points.size >= 4 && points.size % 2 == 0) { "a polyline needs at least two points" }
        moveTo(points[0], points[1])
        for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
    }

    /** A closed circle around ([cx], [cy]) with radius [r]. */
    fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    /** A closed rectangle from ([left], [top]) to ([right], [bottom]) with corner radius [radius]. */
    fun PathBuilder.roundedRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
        moveTo(left + radius, top)
        horizontalLineTo(right - radius)
        quadTo(right, top, right, top + radius)
        verticalLineTo(bottom - radius)
        quadTo(right, bottom, right - radius, bottom)
        horizontalLineTo(left + radius)
        quadTo(left, bottom, left, bottom - radius)
        verticalLineTo(top + radius)
        quadTo(left, top, left + radius, top)
        close()
    }
}
