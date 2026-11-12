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
 * A layout knows three kinds of elements a user can take hold of: boxes, bars and lines. A box is a state, a type or
 * an object. A bar is the bar of a split or join. A line is a connection that can be bent at waypoints.
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
 * A line of a diagram that can be bent at waypoints.
 *
 * @property id the id of the transition or link the line stands for. The layout by hand stores its waypoints by it.
 * @property points the line as it is drawn, from the border of its source to the border of its target.
 */
public data class DiagramLine(public val id: String, public val points: List<DiagramPoint>)
