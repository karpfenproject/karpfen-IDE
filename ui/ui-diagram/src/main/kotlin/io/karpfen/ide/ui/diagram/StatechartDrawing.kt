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

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import io.karpfen.ide.notation.diagram.layout.DiagramBox
import io.karpfen.ide.notation.diagram.layout.EdgeRoute
import io.karpfen.ide.notation.diagram.layout.RouteKind
import io.karpfen.ide.notation.diagram.layout.StatechartLayout
import io.karpfen.ide.notation.diagram.layout.StatechartMetrics
import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.notation.diagram.statechart.StateNode
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.ui.foundation.theme.KarpfenColors
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * The measured texts of a statechart, in pixels. The layout needs the widths, and the drawing needs the laid-out
 * texts themselves.
 *
 * @property names the name of each state, by the name.
 * @property captions the caption of each transition, by the id of the transition.
 * @property notes the line below the name of a state that says which blocks it has, by the name of the state.
 */
internal class StatechartTexts(
    val names: Map<String, TextLayoutResult>,
    val captions: Map<String, TextLayoutResult>,
    val notes: Map<String, TextLayoutResult>,
    private val widths: Map<String, Float>,
) {
    /** The width of [text] in pixels. A text that was not measured is guessed from its length. */
    fun widthOf(text: String): Float = widths[text] ?: (text.length * GUESSED_CHARACTER_WIDTH)

    companion object {
        private const val GUESSED_CHARACTER_WIDTH = 7f

        /** Measures the texts of [diagram] with [measurer] in the three styles of the drawing. */
        fun measure(
            diagram: StatechartDiagram,
            measurer: androidx.compose.ui.text.TextMeasurer,
            nameStyle: TextStyle,
            labelStyle: TextStyle,
            noteStyle: TextStyle,
        ): StatechartTexts {
            val states = diagram.allStates()
            val names = states.associate { it.name to measurer.measure(it.name, nameStyle) }
            val captions = diagram.transitions.associate { it.id to measurer.measure(it.caption, labelStyle) }
            val notes = states.filter { noteOf(it) != null }.associate { it.name to measurer.measure(noteOf(it).orEmpty(), noteStyle) }
            val widths = names.mapValues { it.value.size.width.toFloat() } +
                diagram.transitions.associate { it.caption to captions.getValue(it.id).size.width.toFloat() }
            return StatechartTexts(names, captions, notes, widths)
        }

        /** What the line below the name of [state] says, or null for a state without blocks. */
        private fun noteOf(state: StateNode): String? = when {
            state.hasEntry && state.hasDo -> "entry, do"
            state.hasEntry -> "entry"
            state.hasDo -> "do"
            else -> null
        }
    }
}

/** A statechart that is ready to be drawn: the [diagram], where its elements go, and its measured [texts]. */
internal class StatechartDrawing(val diagram: StatechartDiagram, val layout: StatechartLayout, val texts: StatechartTexts)

/**
 * Draws [drawing] moved by [pan] and scaled by [zoom]. The layout is in density-independent units, so every length is
 * multiplied by the density first.
 *
 * States are drawn parents first, so nested states lie on top of their parent. A state with nested states shows its
 * name in a row at its top. A state without shows it in its middle, with a small line below if it has an entry or a
 * do block. The marker of an initial state is a filled dot. The bars of splits and joins are filled. Every line ends
 * in an arrowhead, except the lines that lead into a bar. The caption of a transition is written on a patch in the
 * colour of the background, so the lines behind it do not cross the text.
 */
internal fun DrawScope.drawStatechart(drawing: StatechartDrawing, colors: KarpfenColors, pan: Offset, zoom: Float) {
    val unit = density
    val layout = drawing.layout
    withTransform({
        translate(pan.x, pan.y)
        scale(zoom, zoom, pivot = Offset.Zero)
    }) {
        for (state in drawing.diagram.allStates()) {
            val box = layout.states[state.name] ?: continue
            drawState(state, box, drawing, colors, unit)
        }
        layout.initialMarkers.values.forEach { marker ->
            drawCircle(colors.text, radius = marker.width * unit / 2f, center = marker.center(unit))
        }
        layout.bars.values.forEach { bar -> drawRect(colors.text, bar.topLeft(unit), bar.size(unit)) }
        layout.routes.forEach { drawRoute(it, colors, unit) }
        layout.routes.forEach { route ->
            val label = route.label ?: return@forEach
            val caption = drawing.texts.captions[route.transitionId] ?: return@forEach
            drawRect(colors.window, label.topLeft(unit), label.size(unit))
            drawText(caption, topLeft = label.topLeft(unit) + Offset(LABEL_INSET * unit, (label.height * unit - caption.size.height) / 2f))
        }
    }
}

/**
 * Draws the points of the grid that elements snap to, moved by [pan] and scaled by [zoom]. Only the points inside the
 * view are drawn. Where the zoom brings them too close together, only every second, fifth or tenth point is drawn.
 */
internal fun DrawScope.drawGrid(colors: KarpfenColors, pan: Offset, zoom: Float) {
    val base = StatechartMetrics.GRID * density * zoom
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

/**
 * Draws the waypoints of [vis] as small handles, moved by [pan] and scaled by [zoom], so the user sees where a
 * transition can be bent. Only the waypoints of transitions that [diagram] has are drawn.
 */
internal fun DrawScope.drawWaypoints(diagram: StatechartDiagram, vis: VisLayout, colors: KarpfenColors, pan: Offset, zoom: Float) {
    val known = diagram.transitions.map { it.id }.toSet()
    val unit = density
    withTransform({
        translate(pan.x, pan.y)
        scale(zoom, zoom, pivot = Offset.Zero)
    }) {
        vis.waypoints.filterKeys(known::contains).values.flatten().forEach { point ->
            val center = Offset(point.x * unit, point.y * unit)
            drawCircle(colors.window, WAYPOINT * unit, center)
            drawCircle(colors.accent, WAYPOINT * unit, center, style = Stroke(LINE * unit))
        }
    }
}

private fun DrawScope.drawState(state: StateNode, box: DiagramBox, drawing: StatechartDrawing, colors: KarpfenColors, unit: Float) {
    val corner = CornerRadius(CORNER * unit)
    val topLeft = box.topLeft(unit)
    val size = box.size(unit)
    drawRoundRect(if (state.isComposite) colors.window else colors.panel, topLeft, size, corner)
    drawRoundRect(colors.border, topLeft, size, corner, style = Stroke(BORDER * unit))
    val name = drawing.texts.names[state.name] ?: return
    if (state.isComposite) {
        val header = drawing.layout.headerHeight * unit
        drawText(name, topLeft = topLeft + Offset(NAME_INSET * unit, (header - name.size.height) / 2f))
        drawLine(colors.border, topLeft + Offset(0f, header), topLeft + Offset(size.width, header), strokeWidth = BORDER * unit)
        return
    }
    val note = drawing.texts.notes[state.name]
    val textHeight = name.size.height + (note?.size?.height ?: 0)
    val top = topLeft.y + (size.height - textHeight) / 2f
    drawText(name, topLeft = Offset(topLeft.x + (size.width - name.size.width) / 2f, top))
    note?.let { drawText(it, topLeft = Offset(topLeft.x + (size.width - it.size.width) / 2f, top + name.size.height)) }
}

private fun DrawScope.drawRoute(route: EdgeRoute, colors: KarpfenColors, unit: Float) {
    val points = route.points.map { Offset(it.x * unit, it.y * unit) }
    if (points.size < 2) return
    val line = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(line, colors.textMuted, style = Stroke(LINE * unit))
    if (route.kind == RouteKind.INTO_BAR) return
    val end = points.last()
    val before = points.dropLast(1).lastOrNull { it != end } ?: return
    val length = hypot(end.x - before.x, end.y - before.y)
    val along = Offset((end.x - before.x) / length, (end.y - before.y) / length)
    val across = Offset(-along.y, along.x)
    val base = end - along * (ARROW_LENGTH * unit)
    val head = Path().apply {
        moveTo(end.x, end.y)
        (base + across * (ARROW_WIDTH * unit / 2f)).let { lineTo(it.x, it.y) }
        (base - across * (ARROW_WIDTH * unit / 2f)).let { lineTo(it.x, it.y) }
        close()
    }
    drawPath(head, colors.textMuted)
}

private fun DiagramBox.topLeft(unit: Float): Offset = Offset(x * unit, y * unit)

private fun DiagramBox.size(unit: Float): Size = Size(width * unit, height * unit)

private fun DiagramBox.center(unit: Float): Offset = Offset((x + width / 2f) * unit, (y + height / 2f) * unit)

/** The radius of the handle of a waypoint. */
private const val WAYPOINT = 4f

/** The radius of a point of the grid. */
private const val GRID_DOT = 0.9f

/** The smallest distance between two drawn points of the grid. */
private const val MIN_GRID_DISTANCE = 8f

/** The radius of the corners of a state. */
private const val CORNER = 8f

/** The width of the border of a state. */
private const val BORDER = 1f

/** The width of the line of a transition. */
private const val LINE = 1.4f

/** The length of an arrowhead. */
private const val ARROW_LENGTH = 9f

/** The width of an arrowhead at its base. */
private const val ARROW_WIDTH = 7f

/** The room left of the name in the top row of a state with nested states. */
private const val NAME_INSET = 10f

/** The room left of a caption on its patch. */
private const val LABEL_INSET = 4f
