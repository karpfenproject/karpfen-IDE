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
package io.karpfen.ide.notation.diagram.layout

import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import io.karpfen.ide.notation.diagram.structure.StructureLink
import io.karpfen.ide.notation.diagram.structure.StructureNode
import kotlin.math.abs

/**
 * The layout of a class or object diagram that was arranged by hand. It draws every element where the [VisLayout]
 * says and does no automatic layout, with one exception: a box the [VisLayout] does not know is new in the text and
 * gets a place of its own.
 *
 * The rules:
 * - A box sits with its middle on its stored point. Its size follows from its texts.
 * - A new box is placed to the right of the placed ones, at their top. Where no box is placed, the row starts at the
 *   top left of the drawing.
 * - A link runs from the border of its source through its waypoints to the border of its target. Without waypoints it
 *   is a straight line. Where a waypoint or the other box is lined up with a box, the line meets that box at a right
 *   angle. A link from a box to itself gets a small loop at its right side.
 * - Several links without waypoints between the same two boxes are drawn side by side, so none hides another.
 * - The label of a link stands beside the middle of its line, to the right of it or above it. Where it would cover
 *   the line of another link there, it stands on the other side if that side is free.
 */
public object ManualStructureLayout {

    /** The distance between two links without waypoints that connect the same two boxes. */
    private const val SPREAD = 30f

    /** The layout of [diagram] as [vis] arranges it, with texts as wide as [text] says. */
    public fun of(diagram: StructureDiagram, vis: VisLayout, text: TextMeasurer): StructureLayout {
        if (diagram.nodes.isEmpty()) return StructureLayout.EMPTY
        val boxes = place(diagram.nodes.distinctBy { it.id }, vis, text)
        val drawable = diagram.links.filter { it.source in boxes && it.target in boxes }
        val straight = drawable.filter { vis.waypoints[it.id].isNullOrEmpty() }.groupBy { setOf(it.source, it.target) }
        val lines = drawable.associate { link ->
            val source = boxes.getValue(link.source)
            val target = boxes.getValue(link.target)
            val waypoints = vis.waypoints[link.id].orEmpty()
            val beside = straight[setOf(link.source, link.target)].orEmpty()
            val shift = if (waypoints.isEmpty()) shiftOf(beside.indexOf(link), beside.size, source, target) else LineRouting.CENTRED
            link.id to LineRouting.between(source, target, waypoints, shift)
        }
        val routes = drawable.map { link ->
            val points = lines.getValue(link.id)
            LinkRoute(link.id, points, labelOn(points, link, text, vis.labels[link.id], others = lines.filterKeys { it != link.id }.values))
        }
        return StructureLayout(boxes, routes)
    }

    /** The boxes of [nodes]: the stored ones where they are stored, the new ones in a row next to them. */
    private fun place(nodes: List<StructureNode>, vis: VisLayout, text: TextMeasurer): Map<String, DiagramBox> {
        val stored = nodes.mapNotNull { node -> vis.boxes[node.id]?.let { node.id to around(it, node, text) } }.toMap()
        var left = stored.values.maxOfOrNull { it.right + StructureMetrics.GAP } ?: StructureMetrics.MARGIN
        val top = stored.values.minOfOrNull { it.y } ?: StructureMetrics.MARGIN
        val boxes = LinkedHashMap<String, DiagramBox>()
        for (node in nodes) {
            boxes[node.id] = stored[node.id] ?: run {
                val width = StructureMetrics.width(node, text)
                val height = StructureMetrics.height(node)
                around(DiagramGrid.snap(DiagramPoint(left + width / 2f, top + height / 2f)), node, text).also {
                    left = it.right + StructureMetrics.GAP
                }
            }
        }
        return boxes
    }

    /** The box of [node] with its middle at [center]. */
    private fun around(center: DiagramPoint, node: StructureNode, text: TextMeasurer): DiagramBox {
        val width = StructureMetrics.width(node, text)
        val height = StructureMetrics.height(node)
        return DiagramBox(center.x - width / 2f, center.y - height / 2f, width, height)
    }

    /**
     * How far the link at [index] of [count] links between [source] and [target] is moved out of the middle. The
     * links are spread across the direction they run in: sideways where the boxes stand above each other, and up and
     * down where they stand next to each other. The loops of links from a box to itself are stacked.
     */
    private fun shiftOf(index: Int, count: Int, source: DiagramBox, target: DiagramBox): DiagramPoint {
        if (source == target) return DiagramPoint(0f, index * SPREAD)
        val offset = (index - (count - 1) / 2f) * SPREAD
        val upright = abs(target.center.y - source.center.y) >= abs(target.center.x - source.center.x)
        return if (upright) DiagramPoint(offset, 0f) else DiagramPoint(0f, offset)
    }

    /**
     * The box of the label of [link] beside the middle of the line through [points], or null for a link without one.
     * The label stands to the right of or above its line. Where it would cover one of the [others], the lines of the
     * other links, and the other side is free, it stands to the left or below instead. A label the user moved stands
     * [offset] away from its usual place and is not looked after any further.
     */
    private fun labelOn(
        points: List<DiagramPoint>,
        link: StructureLink,
        text: TextMeasurer,
        offset: DiagramPoint?,
        others: Collection<List<DiagramPoint>>,
    ): DiagramBox? {
        if (link.label.isEmpty()) return null
        val width = text.widthOf(link.label) + StructureMetrics.LABEL_PADDING
        val usual = LineRouting.captionBeside(points, width, StructureMetrics.LABEL_HEIGHT)
        if (offset != null) return LineRouting.moved(usual, offset)
        if (others.none { Geometry.crosses(usual, it) }) return usual
        val opposite = LineRouting.captionBeside(points, width, StructureMetrics.LABEL_HEIGHT, otherSide = true)
        return if (others.none { Geometry.crosses(opposite, it) }) opposite else usual
    }
}
