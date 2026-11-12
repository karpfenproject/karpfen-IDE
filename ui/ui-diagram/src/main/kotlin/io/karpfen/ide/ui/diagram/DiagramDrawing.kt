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
package io.karpfen.ide.ui.diagram

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import io.karpfen.ide.notation.diagram.layout.DiagramBox
import io.karpfen.ide.notation.diagram.layout.DiagramGrid
import io.karpfen.ide.notation.diagram.layout.DiagramLayout
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.ui.foundation.theme.KarpfenColors
import kotlin.math.ceil
import kotlin.math.hypot

/** A diagram that is ready to be drawn: the [diagram], where its elements go, and its measured [texts]. */
internal class DiagramDrawing<D, L : DiagramLayout>(val diagram: D, val layout: L, val texts: DiagramTexts)

/**
 * Draws the points of the grid that elements snap to, moved by [pan] and scaled by [zoom]. Only the points inside the
 * view are drawn. Where the zoom brings them too close together, only every second, fifth or tenth point is drawn.
 */
internal fun DrawScope.drawGrid(colors: KarpfenColors, pan: Offset, zoom: Float) {
    val base = DiagramGrid.STEP * density * zoom
    val every = listOf(1, 2, 5, 10, 20, 50).firstOrNull { base * it >= MIN_GRID_DISTANCE * density } ?: return
    val step = base * every
    val firstColumn = ceil(-pan.x / step).toInt()
    val firstRow = ceil(-pan.y / step).toInt()
    val radius = GRID_DOT * density
    var column = firstColumn
    while (pan.x + column * step <= size.width) {
        var row = firstRow
        while (pan.y + row * step <= size.height) {
            drawCircle(colors.border, radius, Offset(pan.x + column * step, pan.y + row * step))
            row++
        }
        column++
    }
}

/** Draws [waypoints] as small handles, so the user sees where a line can be bent. */
internal fun DrawScope.drawWaypoints(waypoints: List<DiagramPoint>, colors: KarpfenColors) {
    val unit = density
    waypoints.forEach { point ->
        val center = Offset(point.x * unit, point.y * unit)
        drawCircle(colors.window, WAYPOINT * unit, center)
        drawCircle(colors.accent, WAYPOINT * unit, center, style = Stroke(LINE * unit))
    }
}

/**
 * Draws the marker of a code cell at the left end of [box]: a small circle with a plus, which says that there is code
 * to open. The [word] of the cell, if it has one, is written behind the marker.
 */
internal fun DrawScope.drawCellMarker(box: DiagramBox, word: TextLayoutResult?, colors: KarpfenColors) {
    val unit = density
    val radius = box.height * unit / 2f
    val center = Offset(box.x * unit + radius, (box.y + box.height / 2f) * unit)
    val arm = radius * PLUS_ARM
    drawCircle(colors.panel, radius, center)
    drawCircle(colors.accent, radius, center, style = Stroke(BORDER * unit))
    drawLine(colors.accent, center - Offset(arm, 0f), center + Offset(arm, 0f), strokeWidth = LINE * unit)
    drawLine(colors.accent, center - Offset(0f, arm), center + Offset(0f, arm), strokeWidth = LINE * unit)
    word?.let { drawText(it, color = colors.textMuted, topLeft = Offset(center.x + radius + CELL_WORD_GAP * unit, center.y - it.size.height / 2f)) }
}

/**
 * Marks the boxes a user picks from. Each of the [candidates] gets a thin border in the accent colour, and each of
 * the [picked] ones, which the user clicked already, is filled lightly as well.
 */
internal fun DrawScope.drawPickMarks(candidates: List<DiagramBox>, picked: List<DiagramBox>, colors: KarpfenColors) {
    val unit = density
    candidates.forEach { drawRect(colors.accent, it.topLeft(unit), it.size(unit), style = Stroke(BORDER * unit)) }
    picked.forEach {
        drawRect(colors.accent.copy(alpha = PICKED_ALPHA), it.topLeft(unit), it.size(unit))
        drawRect(colors.accent, it.topLeft(unit), it.size(unit), style = Stroke(GRIP * unit))
    }
}

/** Draws the border of [box] in the accent colour, which shows that the pointer has hold of it to size the box. */
internal fun DrawScope.drawFrameGrip(box: DiagramBox, colors: KarpfenColors) {
    val unit = density
    drawRect(colors.accent, box.topLeft(unit), box.size(unit), style = Stroke(GRIP * unit))
}

/**
 * Draws the line through [points] in [color] and returns it in pixels, or null for a line of fewer than two points,
 * which is not drawn.
 */
internal fun DrawScope.drawPolyline(points: List<DiagramPoint>, color: Color): List<Offset>? {
    val unit = density
    val pixels = points.map { Offset(it.x * unit, it.y * unit) }
    if (pixels.size < 2) return null
    val line = Path().apply {
        moveTo(pixels.first().x, pixels.first().y)
        pixels.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(line, color, style = Stroke(LINE * unit))
    return pixels
}

/**
 * The direction in which the line through [pixels] arrives at its last point, as a vector of length one, or null if
 * all points are the same.
 */
internal fun arrivalOf(pixels: List<Offset>): Offset? {
    val end = pixels.last()
    val before = pixels.dropLast(1).lastOrNull { it != end } ?: return null
    val length = hypot(end.x - before.x, end.y - before.y)
    return Offset((end.x - before.x) / length, (end.y - before.y) / length)
}

/** Draws a filled arrowhead in [color] at the end of the line through [pixels]. */
internal fun DrawScope.drawFilledArrowhead(pixels: List<Offset>, color: Color) {
    val unit = density
    val end = pixels.last()
    val along = arrivalOf(pixels) ?: return
    val across = Offset(-along.y, along.x)
    val base = end - along * (ARROW_LENGTH * unit)
    val head = Path().apply {
        moveTo(end.x, end.y)
        (base + across * (ARROW_WIDTH * unit / 2f)).let { lineTo(it.x, it.y) }
        (base - across * (ARROW_WIDTH * unit / 2f)).let { lineTo(it.x, it.y) }
        close()
    }
    drawPath(head, color)
}

/**
 * Writes [caption] into the box [label] on a patch in the colour of the background, so the lines behind it do not
 * cross the text.
 */
internal fun DrawScope.drawCaption(label: DiagramBox, caption: TextLayoutResult, colors: KarpfenColors) {
    val unit = density
    drawRect(colors.window, label.topLeft(unit), label.size(unit))
    drawText(caption, topLeft = label.topLeft(unit) + Offset(LABEL_INSET * unit, (label.height * unit - caption.size.height) / 2f))
}

/** The top left corner of the box in pixels, where one unit of the diagram is [unit] pixels. */
internal fun DiagramBox.topLeft(unit: Float): Offset = Offset(x * unit, y * unit)

/** The size of the box in pixels, where one unit of the diagram is [unit] pixels. */
internal fun DiagramBox.size(unit: Float): Size = Size(width * unit, height * unit)

/** The middle of the box in pixels, where one unit of the diagram is [unit] pixels. */
internal fun DiagramBox.center(unit: Float): Offset = Offset((x + width / 2f) * unit, (y + height / 2f) * unit)

/** The width of a line between two boxes. */
internal const val LINE: Float = 1.4f

/** The width of the border of a box. */
internal const val BORDER: Float = 1f

/** The length of an arrowhead. */
internal const val ARROW_LENGTH: Float = 9f

/** The width of an arrowhead at its base. */
internal const val ARROW_WIDTH: Float = 7f

/** How far the arms of the plus in the marker of a code cell reach, as a share of the radius of the marker. */
private const val PLUS_ARM = 0.55f

/** The room between the marker of a code cell and its word. */
private const val CELL_WORD_GAP = 4f

/** How strongly a box the user picked is filled with the accent colour. */
private const val PICKED_ALPHA = 0.18f

/** The width of the border of a box while the pointer has hold of it. */
private const val GRIP = 2f

/** The radius of the handle of a waypoint. */
private const val WAYPOINT = 4f

/** The radius of a point of the grid. */
private const val GRID_DOT = 0.9f

/** The smallest distance between two drawn points of the grid. */
private const val MIN_GRID_DISTANCE = 8f

/** The room left of a caption on its patch. */
private const val LABEL_INSET = 4f
