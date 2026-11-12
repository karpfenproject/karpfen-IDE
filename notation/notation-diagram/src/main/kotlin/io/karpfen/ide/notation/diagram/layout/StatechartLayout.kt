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
    /** A whole transition from its source state to its target state. */
    TRANSITION,

    /** The part of a split or join that leads from a source state to its bar. */
    INTO_BAR,

    /** The part of a split or join that leads from its bar to a target state. */
    OUT_OF_BAR,

    /** The short line from the marker of an initial state to that state. */
    INITIAL,
}

/**
 * One line of a laid-out statechart.
 *
 * @property kind what the line stands for.
 * @property transitionId the id of the transition the line belongs to, or null for the line of an initial marker.
 * @property points the line from its start to its end, corner by corner. The end carries the arrowhead.
 * @property label where the label of the transition is written, or null for a line without one. A split or join
 *   carries its label on one of its lines only.
 */
public data class EdgeRoute(
    public val kind: RouteKind,
    public val transitionId: String?,
    public val points: List<DiagramPoint>,
    public val label: DiagramBox? = null,
)

/**
 * Where the elements of a statechart are drawn. All positions are absolute, also those of nested states.
 *
 * @property states the box of each state by its name. The box of a state with nested states surrounds them and
 *   leaves [headerHeight] at its top for the name.
 * @property initialMarkers the dot that marks an initial state, by the name of that state.
 * @property bars the bar of each split and join, by the id of the transition.
 * @property routes the lines of the transitions and of the initial markers.
 * @property width the width of the whole drawing.
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
) {
    /**
     * The smallest rectangle around everything that is drawn. A layout made by hand may reach left of and above the
     * origin, so a view fits this rectangle and not the one from the origin to [width] and [height].
     */
    public val bounds: DiagramBox
        get() {
            val boxes = states.values + initialMarkers.values + bars.values + routes.mapNotNull { it.label } +
                routes.flatMap { route -> route.points.map { DiagramBox(it.x, it.y, 0f, 0f) } }
            return boxes.reduceOrNull(DiagramBox::union) ?: DiagramBox(0f, 0f, 0f, 0f)
        }

    /** The id of the split or join whose bar is drawn at [point], or null. */
    public fun barAt(point: DiagramPoint): String? = bars.entries.firstOrNull { point in it.value.grownBy(HIT_TOLERANCE) }?.key

    /**
     * The line of a normal transition that runs within [tolerance] of [point], or null. Where several do, the nearest
     * one counts.
     */
    public fun transitionAt(point: DiagramPoint, tolerance: Float = HIT_TOLERANCE): EdgeRoute? =
        routes.filter { it.kind == RouteKind.TRANSITION }
            .map { it to Geometry.distanceToLine(point, it.points) }
            .filter { it.second <= tolerance }
            .minByOrNull { it.second }
            ?.first

    private fun DiagramBox.grownBy(by: Float): DiagramBox = DiagramBox(x - by, y - by, width + 2 * by, height + 2 * by)

    /**
     * The name of the state drawn at [point], or null if there is none. Where states are nested, the innermost one
     * counts, which is the smallest box around the point.
     */
    public fun stateAt(point: DiagramPoint): String? =
        states.filterValues { point in it }.minByOrNull { it.value.width * it.value.height }?.key

    public companion object {
        /** How near a point must be to a line, a bar or a waypoint to hit it. */
        public const val HIT_TOLERANCE: Float = 6f

        /** The layout of a statechart without elements. */
        public val EMPTY: StatechartLayout = StatechartLayout(emptyMap(), emptyMap(), emptyMap(), emptyList(), 0f, 0f, 0f)
    }
}
