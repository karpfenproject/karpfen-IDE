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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import io.karpfen.ide.notation.diagram.layout.DiagramBox
import io.karpfen.ide.notation.diagram.layout.StructureLayout
import io.karpfen.ide.notation.diagram.layout.StructureMetrics
import io.karpfen.ide.notation.diagram.structure.LinkKind
import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import io.karpfen.ide.notation.diagram.structure.StructureKind
import io.karpfen.ide.notation.diagram.structure.StructureNode
import io.karpfen.ide.ui.foundation.theme.KarpfenColors
import kotlin.math.hypot

/**
 * Draws a class diagram or an object diagram. Both look alike and follow the usual notation.
 *
 * A box shows its title in its top part and its rows below a dividing line. The title of an object is underlined,
 * which tells an object from a type. A containment link starts with a filled diamond at the box that owns the other.
 * A reference link ends in an open arrowhead at the box it refers to. The label of a link is written on a patch in
 * the colour of the background.
 */
internal object StructurePainter : DiagramPainter<StructureDiagram, StructureLayout> {

    /** The radius of the corners of a box. */
    private const val CORNER = 3f

    /** The length of the diamond of a containment link. */
    private const val DIAMOND_LENGTH = 14f

    /** The width of the diamond of a containment link. */
    private const val DIAMOND_WIDTH = 8f

    /** The distance between the title of an object and the line below it. */
    private const val UNDERLINE_GAP = 1f

    override fun texts(diagram: StructureDiagram): Map<TextRole, Collection<String>> = mapOf(
        TextRole.TITLE to diagram.nodes.map { it.title },
        TextRole.ROW to diagram.nodes.flatMap { it.rows },
        TextRole.LABEL to diagram.links.map { it.label }.filter { it.isNotEmpty() },
    )

    override fun paint(scope: DrawScope, diagram: StructureDiagram, layout: StructureLayout, texts: DiagramTexts, colors: KarpfenColors) {
        for (node in diagram.nodes) {
            val box = layout.boxes[node.id] ?: continue
            scope.drawNode(node, box, diagram.kind == StructureKind.OBJECTS, texts, colors)
        }
        val links = diagram.links.associateBy { it.id }
        layout.routes.forEach { route ->
            val link = links[route.linkId] ?: return@forEach
            val pixels = scope.drawPolyline(route.points, colors.textMuted) ?: return@forEach
            when (link.kind) {
                LinkKind.CONTAINMENT -> scope.drawDiamond(pixels, colors.textMuted)
                LinkKind.REFERENCE -> scope.drawOpenArrowhead(pixels, colors.textMuted)
            }
        }
        layout.routes.forEach { route ->
            val label = route.label ?: return@forEach
            val caption = links[route.linkId]?.let { texts[TextRole.LABEL, it.label] } ?: return@forEach
            scope.drawCaption(label, caption, colors)
        }
    }

    private fun DrawScope.drawNode(node: StructureNode, box: DiagramBox, underlined: Boolean, texts: DiagramTexts, colors: KarpfenColors) {
        val unit = density
        val corner = CornerRadius(CORNER * unit)
        val topLeft = box.topLeft(unit)
        val size = box.size(unit)
        drawRoundRect(colors.panel, topLeft, size, corner)
        drawRoundRect(colors.border, topLeft, size, corner, style = Stroke(BORDER * unit))
        val titleHeight = StructureMetrics.TITLE_HEIGHT * unit
        texts[TextRole.TITLE, node.title]?.let { title ->
            val start = Offset(topLeft.x + (size.width - title.size.width) / 2f, topLeft.y + (titleHeight - title.size.height) / 2f)
            drawText(title, topLeft = start)
            if (underlined) {
                val below = start.y + title.size.height + UNDERLINE_GAP * unit
                drawLine(colors.text, Offset(start.x, below), Offset(start.x + title.size.width, below), strokeWidth = BORDER * unit)
            }
        }
        if (node.rows.isEmpty()) return
        drawLine(colors.border, topLeft + Offset(0f, titleHeight), topLeft + Offset(size.width, titleHeight), strokeWidth = BORDER * unit)
        val rowHeight = StructureMetrics.ROW_HEIGHT * unit
        val first = topLeft.y + titleHeight + StructureMetrics.ROWS_PADDING * unit
        node.rows.forEachIndexed { index, wording ->
            val row = texts[TextRole.ROW, wording] ?: return@forEachIndexed
            drawText(row, topLeft = Offset(topLeft.x + StructureMetrics.TEXT_PADDING * unit, first + index * rowHeight + (rowHeight - row.size.height) / 2f))
        }
    }

    /** Draws a filled diamond in [color] at the start of the line through [pixels], pointing along the line. */
    private fun DrawScope.drawDiamond(pixels: List<Offset>, color: Color) {
        val unit = density
        val start = pixels.first()
        val next = pixels.drop(1).firstOrNull { it != start } ?: return
        val length = hypot(next.x - start.x, next.y - start.y)
        val along = Offset((next.x - start.x) / length, (next.y - start.y) / length)
        val across = Offset(-along.y, along.x)
        val middle = start + along * (DIAMOND_LENGTH * unit / 2f)
        val tip = start + along * (DIAMOND_LENGTH * unit)
        val diamond = Path().apply {
            moveTo(start.x, start.y)
            (middle + across * (DIAMOND_WIDTH * unit / 2f)).let { lineTo(it.x, it.y) }
            lineTo(tip.x, tip.y)
            (middle - across * (DIAMOND_WIDTH * unit / 2f)).let { lineTo(it.x, it.y) }
            close()
        }
        drawPath(diamond, color)
    }

    /** Draws an open arrowhead in [color] at the end of the line through [pixels]. */
    private fun DrawScope.drawOpenArrowhead(pixels: List<Offset>, color: Color) {
        val unit = density
        val end = pixels.last()
        val along = arrivalOf(pixels) ?: return
        val across = Offset(-along.y, along.x)
        val base = end - along * (ARROW_LENGTH * unit)
        val head = Path().apply {
            (base + across * (ARROW_WIDTH * unit / 2f)).let { moveTo(it.x, it.y) }
            lineTo(end.x, end.y)
            (base - across * (ARROW_WIDTH * unit / 2f)).let { lineTo(it.x, it.y) }
        }
        drawPath(head, color, style = Stroke(LINE * unit))
    }
}
