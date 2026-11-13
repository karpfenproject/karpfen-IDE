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

import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.statechart.TransitionKind

/**
 * The changes a user makes to the layout of a diagram by hand. The changes of waypoints and bars serve every kind of
 * diagram. [freeze], [moveState] and [prune] are those of a statechart. Every function takes a [VisLayout] and returns a
 * new one, so a view can try a change while the pointer moves and keep or drop the result.
 *
 * The first change by hand starts from [freeze], which turns the layout on screen, usually the automatic one, into a
 * [VisLayout] with every element in it. From then on nothing is placed automatically, except what is new in the text.
 * Every position a function stores is on the grid.
 */
public object VisEditing {

    /**
     * The layout [layout] of [diagram] as a [VisLayout]: the middle of every state without nested states, the middle
     * of every bar, and the corners of every normal transition as its waypoints, all moved onto the grid.
     */
    public fun freeze(diagram: StatechartDiagram, layout: StatechartLayout): VisLayout = VisLayout(
        boxes = diagram.allStates()
            .filter { !it.isComposite }
            .mapNotNull { state -> layout.states[state.name]?.let { state.name to DiagramGrid.snap(it.center) } }
            .toMap(),
        bars = layout.bars.mapValues { DiagramGrid.snap(it.value.center) },
        waypoints = layout.routes
            .filter { it.kind == RouteKind.TRANSITION && it.transitionId != null && it.points.size > 2 }
            .associate { route ->
                checkNotNull(route.transitionId) to route.points.subList(1, route.points.size - 1).map(DiagramGrid::snap).distinct()
            },
        frames = diagram.allStates()
            .filter { it.isComposite }
            .mapNotNull { state -> layout.states[state.name]?.let { state.name to snapped(it) } }
            .toMap(),
    )

    /** [box] with its four sides moved onto the grid. */
    private fun snapped(box: DiagramBox): DiagramBox {
        val left = DiagramGrid.snap(box.x)
        val top = DiagramGrid.snap(box.y)
        return DiagramBox(left, top, DiagramGrid.snap(box.right) - left, DiagramGrid.snap(box.bottom) - top)
    }

    /**
     * [vis] with the label of the line [id] moved by [delta] from where it stands in [vis]. The move is rounded to
     * the grid.
     */
    public fun moveLabel(vis: VisLayout, id: String, delta: DiagramPoint): VisLayout {
        val step = DiagramGrid.snap(delta)
        if (step.x == 0f && step.y == 0f) return vis
        val from = vis.labels[id] ?: DiagramPoint(0f, 0f)
        return vis.copy(labels = vis.labels + (id to from + step))
    }

    /** [vis] with the state [name], which has nested states, sized to [frame]. */
    public fun resize(diagram: StatechartDiagram, vis: VisLayout, name: String, frame: DiagramBox): VisLayout =
        if (diagram.state(name)?.isComposite == true) vis.copy(frames = vis.frames + (name to frame)) else vis

    /**
     * [vis] with the state [name] moved by [delta]. A state with nested states takes all of them along, together
     * with their frames and the waypoints of the transitions that run between them. The move is rounded to the grid as a whole, so
     * what moves together keeps its distances.
     */
    public fun moveState(diagram: StatechartDiagram, vis: VisLayout, name: String, delta: DiagramPoint): VisLayout {
        val state = diagram.state(name) ?: return vis
        val step = DiagramGrid.snap(delta)
        if (step.x == 0f && step.y == 0f) return vis
        val moved = state.withDescendants().map { it.name }.toSet()
        val inside = diagram.transitions
            .filter { state.isComposite && (it.sources + it.targets).all(moved::contains) }
            .map { it.id }
            .toSet()
        return vis.copy(
            boxes = vis.boxes.mapValues { (key, point) -> if (key in moved) point + step else point },
            bars = vis.bars.mapValues { (id, point) -> if (id in inside) point + step else point },
            waypoints = vis.waypoints.mapValues { (id, points) -> if (id in inside) points.map { it + step } else points },
            frames = vis.frames.mapValues { (key, box) -> if (key in moved) box.copy(x = box.x + step.x, y = box.y + step.y) else box },
        )
    }

    /** [vis] with the bar of the split or join [id] moved to [to]. */
    public fun moveBar(vis: VisLayout, id: String, to: DiagramPoint): VisLayout =
        vis.copy(bars = vis.bars + (id to DiagramGrid.snap(to)))

    /** [vis] with the waypoint at [index] of the line [id] moved to [to]. */
    public fun moveWaypoint(vis: VisLayout, id: String, index: Int, to: DiagramPoint): VisLayout {
        val points = vis.waypoints[id] ?: return vis
        if (index !in points.indices) return vis
        return vis.copy(waypoints = vis.waypoints + (id to points.toMutableList().also { it[index] = DiagramGrid.snap(to) }))
    }

    /**
     * [vis] with a new waypoint of the line [id] at [at]. [line] is the line as it is drawn, from the border of its
     * source to the border of its target. The new waypoint goes into the segment of that line
     * that is nearest to [at], so the line bends where the user pointed.
     */
    public fun addWaypoint(vis: VisLayout, id: String, at: DiagramPoint, line: List<DiagramPoint>): VisLayout {
        val points = vis.waypoints[id].orEmpty()
        val index = Geometry.nearestSegment(at, line).coerceIn(0, points.size)
        return vis.copy(waypoints = vis.waypoints + (id to points.toMutableList().also { it.add(index, DiagramGrid.snap(at)) }))
    }

    /** [vis] without the waypoint at [index] of the line [id]. */
    public fun removeWaypoint(vis: VisLayout, id: String, index: Int): VisLayout {
        val points = vis.waypoints[id] ?: return vis
        if (index !in points.indices) return vis
        val rest = points.filterIndexed { position, _ -> position != index }
        return vis.copy(waypoints = if (rest.isEmpty()) vis.waypoints - id else vis.waypoints + (id to rest))
    }

    /**
     * The names that changed between two versions of a list of elements, each old name with its new one. The lists
     * are the elements of a diagram before and after a change of the text, in the order of the text. Two lists of
     * the same length are compared place by place. A name counts as changed if the old one is gone and the new one
     * was not there before. So an element that was renamed in the text is found, and elements that only changed
     * places are not taken for renamed. Lists of different lengths give nothing, because then elements were added or
     * removed.
     */
    public fun renamed(before: List<String>, after: List<String>): Map<String, String> {
        if (before.size != after.size) return emptyMap()
        val was = before.toSet()
        val now = after.toSet()
        return before.zip(after).filter { (old, new) -> old != new && old !in now && new !in was }.toMap()
    }

    /**
     * [vis] with its entries moved to new ids: the boxes and frames as [boxes] says, and the bars, waypoints and
     * labels as [lines] says, each from an old id to a new one. So an element that was renamed keeps its place.
     */
    public fun rekeyed(vis: VisLayout, boxes: Map<String, String>, lines: Map<String, String>): VisLayout {
        if (boxes.isEmpty() && lines.isEmpty()) return vis
        fun <V> Map<String, V>.by(names: Map<String, String>): Map<String, V> = mapKeys { names[it.key] ?: it.key }
        return VisLayout(vis.boxes.by(boxes), vis.bars.by(lines), vis.waypoints.by(lines), vis.labels.by(lines), vis.frames.by(boxes))
    }

    /**
     * The waypoint of [vis] within [tolerance] of [point], as the id of its line and its index, or null. Only the
     * lines with an id in [lines] count.
     */
    public fun waypointAt(
        vis: VisLayout,
        lines: Set<String>,
        point: DiagramPoint,
        tolerance: Float = DiagramLayout.HIT_TOLERANCE,
    ): Pair<String, Int>? =
        vis.waypoints
            .filterKeys(lines::contains)
            .flatMap { (id, points) -> points.mapIndexed { index, waypoint -> Triple(id, index, Geometry.distance(point, waypoint)) } }
            .filter { it.third <= tolerance }
            .minByOrNull { it.third }
            ?.let { it.first to it.second }

    /**
     * [vis] with only what [diagram] still has. The text is the source of truth, so the place of a state that is gone
     * from the text, or was given nested states, is dropped, and so are the bars, waypoints and labels of transitions
     * that are gone and the frames of states that have no nested states any more. This is what is written to the
     * companion file.
     */
    public fun prune(diagram: StatechartDiagram, vis: VisLayout): VisLayout {
        val leaves = diagram.allStates().filter { !it.isComposite }.map { it.name }.toSet()
        val forks = diagram.transitions.filter { it.kind != TransitionKind.NORMAL }.map { it.id }.toSet()
        val normal = diagram.transitions.filter { it.kind == TransitionKind.NORMAL }.map { it.id }.toSet()
        val composites = diagram.allStates().filter { it.isComposite }.map { it.name }.toSet()
        return VisLayout(
            boxes = vis.boxes.filterKeys(leaves::contains),
            bars = vis.bars.filterKeys(forks::contains),
            waypoints = vis.waypoints.filterKeys(normal::contains).filterValues { it.isNotEmpty() },
            labels = vis.labels.filterKeys { it in forks || it in normal },
            frames = vis.frames.filterKeys(composites::contains),
        )
    }

    private operator fun DiagramPoint.plus(other: DiagramPoint): DiagramPoint = DiagramPoint(x + other.x, y + other.y)
}
