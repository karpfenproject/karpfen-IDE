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

/** A point of a diagram, in the diagram's own units from its top left corner. */
public data class DiagramPoint(public val x: Float, public val y: Float)

/** A rectangle of a diagram, in the diagram's own units from its top left corner. */
public data class DiagramBox(public val x: Float, public val y: Float, public val width: Float, public val height: Float) {
    /** The right edge. */
    public val right: Float get() = x + width

    /** The lower edge. */
    public val bottom: Float get() = y + height

    /** The middle of the rectangle. */
    public val center: DiagramPoint get() = DiagramPoint(x + width / 2f, y + height / 2f)

    /** The smallest rectangle around this one and [other]. */
    public fun union(other: DiagramBox): DiagramBox {
        val left = minOf(x, other.x)
        val top = minOf(y, other.y)
        return DiagramBox(left, top, maxOf(right, other.right) - left, maxOf(bottom, other.bottom) - top)
    }

    /** True if [point] lies in the rectangle or on its border. */
    public operator fun contains(point: DiagramPoint): Boolean = point.x in x..right && point.y in y..bottom
}

/** What a line of a laid-out statechart stands for. */
public enum class RouteKind {
    /** A whole normal transition from its source state to its target state. */
    TRANSITION,

    /** The part of a split or join that leads from a source state to its bar. */
    INTO_BAR,

    /** The part of a split or join that leads from its bar to a target state. */
    OUT_OF_BAR,

    /** The timeout of a state, from that state to the state the timeout leads to. */
    TIMEOUT,

    /** The short line from the marker of an initial state to that state. */
    INITIAL,
}

/**
 * One line of a laid-out statechart.
 *
 * @property kind what the line stands for.
 * @property edgeId the id of the edge the line belongs to, a transition or a timeout, or null for the line of an
 *   initial marker. The several lines of a split or join share the id of their transition.
 * @property points the line from its start to its end, corner by corner. The end carries the arrowhead.
 * @property label where the caption of the edge is written, or null for a line without one. A split or join
 *   carries its caption on one of its lines only.
 * @property lineId the id of this line, by which the layout by hand stores its waypoints, or null for the line of an
 *   initial marker, which cannot be bent. It is the id of the edge for a normal transition and a timeout, and one of
 *   the [io.karpfen.ide.notation.diagram.statechart.StatechartEdge.lineIds] of a split or join for its lines.
 */
public data class EdgeRoute(
    public val kind: RouteKind,
    public val edgeId: String?,
    public val points: List<DiagramPoint>,
    public val label: DiagramBox? = null,
    public val lineId: String? = edgeId,
)

/**
 * Where the elements of a statechart are drawn. All positions are absolute, also those of nested states.
 *
 * @property states the box of each state by its name. The box of a state with nested states surrounds them and
 *   leaves [headerHeight] at its top for the name.
 * @property initialMarkers the dot that marks an initial state, by the name of that state.
 * @property bars the bar of each split and join, by the id of the transition.
 * @property routes the lines of the transitions, of the timeouts and of the initial markers.
 * @property width the width of the whole drawing. A layout made by hand may reach left of and above the origin, so a
 *   view fits [bounds] and not the rectangle from the origin to [width] and [height].
 * @property height the height of the whole drawing.
 * @property headerHeight the room at the top of a state's box that holds its name.
 */
public data class StatechartLayout(
    public val states: Map<String, DiagramBox>,
    public val initialMarkers: Map<String, DiagramBox>,
    public val bars: Map<String, DiagramBox>,
    public val routes: List<EdgeRoute>,
    public val width: Float,
    public val height: Float,
    public val headerHeight: Float,
) : DiagramLayout {
    override val bounds: DiagramBox
        get() {
            val boxes = states.values + initialMarkers.values + bars.values + routes.mapNotNull { it.label } +
                routes.flatMap { route -> route.points.map { DiagramBox(it.x, it.y, 0f, 0f) } }
            return boxes.reduceOrNull(DiagramBox::union) ?: DiagramBox(0f, 0f, 0f, 0f)
        }

    /** The id of the split or join whose bar is drawn at [point], or null. */
    override fun barAt(point: DiagramPoint): String? = bars.entries.firstOrNull { point in it.value.grownBy(HIT_TOLERANCE) }?.key

    /**
     * The line that can be bent and runs within [tolerance] of [point], or null. These are the lines of all edges,
     * not those of the initial markers. Where several do, the nearest one counts.
     */
    public fun routeAt(point: DiagramPoint, tolerance: Float = HIT_TOLERANCE): EdgeRoute? =
        routes.filter { it.lineId != null }
            .map { it to Geometry.distanceToLine(point, it.points) }
            .filter { it.second <= tolerance }
            .minByOrNull { it.second }
            ?.first

    override fun boxAt(point: DiagramPoint): String? = stateAt(point)

    override fun boxOf(id: String): DiagramBox? = states[id]

    override val labels: Map<String, DiagramBox>
        get() = routes.mapNotNull { route -> route.edgeId?.let { id -> route.label?.let { id to it } } }.toMap()

    /**
     * The hold of [point] on the border of a state with nested states. Those are the states whose box surrounds the
     * box of another state. Where several borders are near, the innermost state counts.
     */
    override fun frameAt(point: DiagramPoint, tolerance: Float): FrameGrip? =
        states.entries
            .filter { (_, box) -> states.values.any { Geometry.contains(box, it) } }
            .sortedBy { it.value.width * it.value.height }
            .firstNotNullOfOrNull { (name, box) -> FrameGrip.on(name, box, point, tolerance) }

    override fun lineAt(point: DiagramPoint, tolerance: Float): DiagramLine? {
        val route = routeAt(point, tolerance) ?: return null
        return route.lineId?.let { DiagramLine(it, route.points) }
    }

    private fun DiagramBox.grownBy(by: Float): DiagramBox = DiagramBox(x - by, y - by, width + 2 * by, height + 2 * by)

    /**
     * The name of the state drawn at [point], or null if there is none. Where states are nested, the innermost one
     * counts, which is the smallest box around the point.
     */
    public fun stateAt(point: DiagramPoint): String? =
        states.filterValues { point in it }.minByOrNull { it.value.width * it.value.height }?.key

    public companion object {
        /** How near a point must be to a line, a bar or a waypoint to hit it. */
        public const val HIT_TOLERANCE: Float = DiagramLayout.HIT_TOLERANCE

        /** The layout of a statechart without elements. */
        public val EMPTY: StatechartLayout = StatechartLayout(emptyMap(), emptyMap(), emptyMap(), emptyList(), 0f, 0f, 0f)
    }
}
