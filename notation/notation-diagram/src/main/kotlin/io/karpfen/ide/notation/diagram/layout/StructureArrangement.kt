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

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.notation.diagram.structure.StructureDiagram

/**
 * How a class or object diagram is laid out. The boxes are its types or objects, by their ids. Every link can be bent
 * at waypoints. There are no bars.
 */
public object StructureArrangement : DiagramArrangement<StructureDiagram, StructureLayout> {

    override fun isEmpty(diagram: StructureDiagram): Boolean = diagram.nodes.isEmpty()

    override fun automatic(diagram: StructureDiagram, text: TextMeasurer): StructureLayout = StructureLayouter().layout(diagram, text)

    override fun byHand(diagram: StructureDiagram, vis: VisLayout, text: TextMeasurer): StructureLayout =
        ManualStructureLayout.of(diagram, vis, text)

    /**
     * The [layout] of [diagram] as a [VisLayout]: the middle of every box, and the corners of every link as its
     * waypoints, all moved onto the grid.
     *
     * A link without corners needs no waypoint if it runs where a straight line by hand would run. The automatic
     * layout also draws straight lines that leave a box off its middle, to make room for a second line next to them.
     * Such a line gets one waypoint in its middle, so it stays where it is.
     */
    override fun freeze(diagram: StructureDiagram, layout: StructureLayout): VisLayout {
        val links = diagram.links.associateBy { it.id }
        val waypoints = layout.routes.mapNotNull { route ->
            val inner = if (route.points.size > 2) {
                route.points.subList(1, route.points.size - 1)
            } else {
                val link = links[route.linkId]
                val source = link?.let { layout.boxes[it.source] }
                val target = link?.let { layout.boxes[it.target] }
                if (source == null || target == null || isCentred(route.points, source, target)) emptyList() else listOf(Geometry.middle(route.points))
            }
            inner.map(DiagramGrid::snap).distinct().takeIf { it.isNotEmpty() }?.let { route.linkId to it }
        }
        return VisLayout(boxes = layout.boxes.mapValues { DiagramGrid.snap(it.value.center) }, waypoints = waypoints.toMap())
    }

    /** True if the straight line through [points] runs about where a line by hand from [source] to [target] would. */
    private fun isCentred(points: List<DiagramPoint>, source: DiagramBox, target: DiagramBox): Boolean {
        if (points.size < 2) return true
        val byHand = LineRouting.between(source, target, emptyList())
        return Geometry.distance(points.first(), byHand.first()) < DiagramGrid.STEP &&
            Geometry.distance(points.last(), byHand.last()) < DiagramGrid.STEP
    }

    /**
     * [vis] with the box [box] moved by [delta], which is rounded to the grid. The waypoints of the links from the
     * box to itself move along, so their loops keep their shape.
     */
    override fun moveBox(diagram: StructureDiagram, vis: VisLayout, box: String, delta: DiagramPoint): VisLayout {
        val from = vis.boxes[box] ?: return vis
        val step = DiagramGrid.snap(delta)
        if (step.x == 0f && step.y == 0f) return vis
        val loops = diagram.links.filter { it.source == box && it.target == box }.map { it.id }.toSet()
        return vis.copy(
            boxes = vis.boxes + (box to DiagramPoint(from.x + step.x, from.y + step.y)),
            waypoints = vis.waypoints.mapValues { (id, points) ->
                if (id in loops) points.map { DiagramPoint(it.x + step.x, it.y + step.y) } else points
            },
        )
    }

    override fun prune(diagram: StructureDiagram, vis: VisLayout): VisLayout {
        val nodes = diagram.nodes.map { it.id }.toSet()
        return VisLayout(
            boxes = vis.boxes.filterKeys(nodes::contains),
            waypoints = vis.waypoints.filterKeys(lines(diagram)::contains).filterValues { it.isNotEmpty() },
        )
    }

    override fun lines(diagram: StructureDiagram): Set<String> = diagram.links.map { it.id }.toSet()

    override fun placeInText(diagram: StructureDiagram, box: String): TextRange? = diagram.node(box)?.nameRange
}
