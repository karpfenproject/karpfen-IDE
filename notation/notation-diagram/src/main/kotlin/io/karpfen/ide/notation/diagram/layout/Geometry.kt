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

import kotlin.math.abs
import kotlin.math.hypot

/** The plane geometry the layouts need: distances, the point where a line leaves a box, and the middle of a line. */
internal object Geometry {

    /** The distance between [a] and [b]. */
    fun distance(a: DiagramPoint, b: DiagramPoint): Float = hypot(a.x - b.x, a.y - b.y)

    /** The distance from [point] to the segment from [a] to [b]. */
    fun distanceToSegment(point: DiagramPoint, a: DiagramPoint, b: DiagramPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return distance(point, a)
        val along = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return distance(point, DiagramPoint(a.x + along * dx, a.y + along * dy))
    }

    /** The distance from [point] to the line through [points], or infinity for a line of fewer than two points. */
    fun distanceToLine(point: DiagramPoint, points: List<DiagramPoint>): Float =
        points.zipWithNext().minOfOrNull { (a, b) -> distanceToSegment(point, a, b) } ?: Float.POSITIVE_INFINITY

    /**
     * The index of the segment of the line through [points] that is nearest to [point]. Segment `n` runs from point
     * `n` to point `n + 1`.
     */
    fun nearestSegment(point: DiagramPoint, points: List<DiagramPoint>): Int =
        points.zipWithNext().withIndex().minByOrNull { (_, ends) -> distanceToSegment(point, ends.first, ends.second) }?.index ?: 0

    /**
     * Where a line that leaves [box] towards [toward] starts on the border of [box].
     *
     * If [toward] lies straight above or below the box, the line leaves the top or bottom border at the height of
     * [toward], and likewise sideways. So a line to a point that is lined up with the box is horizontal or vertical,
     * which keeps drawings on a grid tidy. Otherwise the line runs from the middle of the box, and the result is where
     * it crosses the border. If [toward] lies inside the box, it is the point of the border nearest to [toward].
     */
    fun anchor(box: DiagramBox, toward: DiagramPoint): DiagramPoint {
        val center = box.center
        val withinWidth = toward.x >= box.x && toward.x <= box.right
        val withinHeight = toward.y >= box.y && toward.y <= box.bottom
        if (withinWidth && withinHeight) {
            val left = toward.x - box.x
            val right = box.right - toward.x
            val top = toward.y - box.y
            val bottom = box.bottom - toward.y
            return when (minOf(left, right, top, bottom)) {
                top -> DiagramPoint(toward.x, box.y)
                bottom -> DiagramPoint(toward.x, box.bottom)
                left -> DiagramPoint(box.x, toward.y)
                else -> DiagramPoint(box.right, toward.y)
            }
        }
        if (withinWidth) return DiagramPoint(toward.x, if (toward.y < box.y) box.y else box.bottom)
        if (withinHeight) return DiagramPoint(if (toward.x < box.x) box.x else box.right, toward.y)
        val dx = toward.x - center.x
        val dy = toward.y - center.y
        val scale = minOf((box.width / 2f) / abs(dx), (box.height / 2f) / abs(dy))
        return DiagramPoint(center.x + dx * scale, center.y + dy * scale)
    }

    /** True if the line through [points] runs through [box] or touches it. */
    fun crosses(box: DiagramBox, points: List<DiagramPoint>): Boolean =
        points.zipWithNext().any { (a, b) -> crosses(box, a, b) } || points.size == 1 && points.first() in box

    /**
     * True if the segment from [a] to [b] runs through [box] or touches it. The segment is cut down to the part
     * between the left and right border and then to the part between the upper and lower border. It crosses the box
     * if something is left.
     */
    private fun crosses(box: DiagramBox, a: DiagramPoint, b: DiagramPoint): Boolean {
        var from = 0f
        var to = 1f
        for ((start, delta, low, high) in listOf(
            Span(a.x, b.x - a.x, box.x, box.right),
            Span(a.y, b.y - a.y, box.y, box.bottom),
        )) {
            if (delta == 0f) {
                if (start < low || start > high) return false
            } else {
                val first = (low - start) / delta
                val second = (high - start) / delta
                from = maxOf(from, minOf(first, second))
                to = minOf(to, maxOf(first, second))
                if (from > to) return false
            }
        }
        return true
    }

    /** One direction of a segment against a box: where it starts, how far it runs, and the two borders. */
    private data class Span(val start: Float, val delta: Float, val low: Float, val high: Float)

    /** True if [inner] lies within [outer], as the box of a nested state lies within the box of its parent. */
    fun contains(outer: DiagramBox, inner: DiagramBox): Boolean =
        outer != inner && inner.x >= outer.x && inner.right <= outer.right && inner.y >= outer.y && inner.bottom <= outer.bottom

    /** The point halfway along the line through [points], measured by its length. */
    fun middle(points: List<DiagramPoint>): DiagramPoint = middleOf(points).first

    /** True if the line through [points] runs more up and down than sideways where its middle is. */
    fun isUprightAtMiddle(points: List<DiagramPoint>): Boolean = middleOf(points).second

    /** The middle of the line through [points] and whether the segment it lies on is more upright than flat. */
    private fun middleOf(points: List<DiagramPoint>): Pair<DiagramPoint, Boolean> {
        if (points.isEmpty()) return DiagramPoint(0f, 0f) to false
        val half = points.zipWithNext().sumOf { (a, b) -> distance(a, b).toDouble() }.toFloat() / 2f
        var walked = 0f
        for ((a, b) in points.zipWithNext()) {
            val length = distance(a, b)
            if (walked + length >= half && length > 0f) {
                val along = (half - walked) / length
                return DiagramPoint(a.x + (b.x - a.x) * along, a.y + (b.y - a.y) * along) to (abs(b.y - a.y) >= abs(b.x - a.x))
            }
            walked += length
        }
        return points.last() to false
    }
}
