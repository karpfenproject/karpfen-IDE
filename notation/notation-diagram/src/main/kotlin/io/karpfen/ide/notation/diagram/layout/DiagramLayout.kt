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

/**
 * Where the elements of a diagram are drawn, as far as a view needs it to move the drawing and to let the user
 * arrange it by hand. Every kind of diagram has its own layout class with the details of its drawing. This interface
 * is what all of them share.
 *
 * A layout knows the elements a user can take hold of. A box is a state, a type or an object. A bar is the bar of a
 * split or join. A line is a connection that can be bent at waypoints. A label is the caption of a line. A frame is a
 * box that the user can size, which is a state with nested states.
 */
public interface DiagramLayout {
    /**
     * The smallest rectangle around everything that is drawn. A layout made by hand may reach left of and above the
     * origin, so a view fits this rectangle.
     */
    public val bounds: DiagramBox

    /** The id of the box drawn at [point], or null. Where boxes lie in each other, the innermost one counts. */
    public fun boxAt(point: DiagramPoint): String?

    /** The id of the bar drawn at [point], or null. */
    public fun barAt(point: DiagramPoint): String?

    /** The box with the id [id], or null if the layout has no such box. */
    public fun boxOf(id: String): DiagramBox?

    /** The box of the label of each line that has one, by the id of the line. */
    public val labels: Map<String, DiagramBox>

    /** The id of the line whose label is drawn at [point], or null. */
    public fun labelAt(point: DiagramPoint): String? = labels.entries.firstOrNull { point in it.value }?.key

    /**
     * Where [point] takes hold of the border of a box that can be sized, or null. The point must lie within
     * [tolerance] of the border.
     */
    public fun frameAt(point: DiagramPoint, tolerance: Float): FrameGrip?

    /**
     * The line that can be bent and runs within [tolerance] of [point], or null. Where several do, the nearest one
     * counts.
     */
    public fun lineAt(point: DiagramPoint, tolerance: Float): DiagramLine?

    public companion object {
        /** How near a point must be to a line, a bar or a waypoint to hit it. */
        public const val HIT_TOLERANCE: Float = 6f
    }
}

/**
 * A hold on the border of a box that can be sized. A hold on a corner has two of the four sides.
 *
 * @property id the id of the box.
 * @property left true if the hold is on the left side, which a drag then moves.
 * @property top true if the hold is on the upper side.
 * @property right true if the hold is on the right side.
 * @property bottom true if the hold is on the lower side.
 */
public data class FrameGrip(
    public val id: String,
    public val left: Boolean,
    public val top: Boolean,
    public val right: Boolean,
    public val bottom: Boolean,
) {
    /**
     * [box] with the sides of this hold moved to [point] and onto the grid. A side never crosses the opposite one:
     * the box keeps at least the size of two steps of the grid.
     */
    public fun resized(box: DiagramBox, point: DiagramPoint): DiagramBox {
        val least = 2 * DiagramGrid.STEP
        val x = DiagramGrid.snap(point.x)
        val y = DiagramGrid.snap(point.y)
        val newLeft = if (left) minOf(x, box.right - least) else box.x
        val newTop = if (top) minOf(y, box.bottom - least) else box.y
        val newRight = if (right) maxOf(x, newLeft + least) else box.right
        val newBottom = if (bottom) maxOf(y, newTop + least) else box.bottom
        return DiagramBox(newLeft, newTop, newRight - newLeft, newBottom - newTop)
    }

    public companion object {
        /**
         * The hold that [point] has on the border of the box [box] with the id [id], or null if the point is farther
         * than [tolerance] from the border.
         */
        public fun on(id: String, box: DiagramBox, point: DiagramPoint, tolerance: Float): FrameGrip? {
            val within = point.x >= box.x - tolerance && point.x <= box.right + tolerance &&
                point.y >= box.y - tolerance && point.y <= box.bottom + tolerance
            if (!within) return null
            val left = kotlin.math.abs(point.x - box.x) <= tolerance
            val right = !left && kotlin.math.abs(point.x - box.right) <= tolerance
            val top = kotlin.math.abs(point.y - box.y) <= tolerance
            val bottom = !top && kotlin.math.abs(point.y - box.bottom) <= tolerance
            return if (left || right || top || bottom) FrameGrip(id, left, top, right, bottom) else null
        }
    }
}

/**
 * A line of a diagram that can be bent at waypoints.
 *
 * @property id the id of the transition or link the line stands for. The layout by hand stores its waypoints by it.
 * @property points the line as it is drawn, from the border of its source to the border of its target.
 */
public data class DiagramLine(public val id: String, public val points: List<DiagramPoint>)
