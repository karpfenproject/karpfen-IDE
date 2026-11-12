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
 * How a line runs between two boxes of a diagram that is arranged by hand, and where its caption stands. The layouts
 * by hand of all kinds of diagram share these rules.
 */
internal object LineRouting {

    /** The distance between a line and the caption beside it. */
    private const val LABEL_DISTANCE = 4f

    /** How far the loop of a line from a box to itself reaches to the right of the box. */
    private const val LOOP_REACH = 30f

    /** Half the height of the loop of a line from a box to itself. */
    private const val LOOP_HALF_HEIGHT = 10f

    /** No shift. */
    val CENTRED: DiagramPoint = DiagramPoint(0f, 0f)

    /**
     * The line from the border of [source] through [waypoints] to the border of [target].
     *
     * Without waypoints it is a straight line. Where a waypoint or the other box is lined up with a box, the line
     * meets that box at a right angle. A line between a box and one that lies in it runs straight down from the top
     * border of the outer box to the top of the inner one. A line from a box to itself gets a small loop at its right
     * side.
     *
     * [shift] moves a line without waypoints sideways, so several lines between the same two boxes do not lie on top
     * of each other. It moves the point of [target] the line aims at, and the loop of a line from a box to itself.
     */
    fun between(
        source: DiagramBox,
        target: DiagramBox,
        waypoints: List<DiagramPoint>,
        shift: DiagramPoint = CENTRED,
    ): List<DiagramPoint> {
        val through = when {
            waypoints.isNotEmpty() -> waypoints
            source == target -> listOf(
                DiagramPoint(source.right + LOOP_REACH + shift.x, source.center.y - LOOP_HALF_HEIGHT + shift.y),
                DiagramPoint(source.right + LOOP_REACH + shift.x, source.center.y + LOOP_HALF_HEIGHT + shift.y),
            )
            else -> emptyList()
        }
        return when {
            through.isEmpty() && Geometry.contains(source, target) ->
                listOf(DiagramPoint(target.center.x, source.y), DiagramPoint(target.center.x, target.y))
            through.isEmpty() && Geometry.contains(target, source) ->
                listOf(DiagramPoint(source.center.x, source.y), DiagramPoint(source.center.x, target.y))
            else -> {
                val aim = DiagramPoint(target.center.x + shift.x, target.center.y + shift.y)
                val start = Geometry.anchor(source, through.firstOrNull() ?: aim)
                listOf(start) + through + Geometry.anchor(target, through.lastOrNull() ?: start)
            }
        }
    }

    /** [caption] moved by [offset], which is how far the user moved it, or as it is without an offset. */
    fun moved(caption: DiagramBox, offset: DiagramPoint?): DiagramBox =
        if (offset == null) caption else caption.copy(x = caption.x + offset.x, y = caption.y + offset.y)

    /**
     * The box of a caption that is [width] wide and [height] high at the middle of the line through [points]. The
     * caption stands beside the line and not on it: to the right of a part that runs up and down, and above a part
     * that runs sideways. With [otherSide] it stands to the left or below instead, which keeps the captions of lines
     * that run side by side out of each other's way.
     */
    fun captionBeside(points: List<DiagramPoint>, width: Float, height: Float, otherSide: Boolean = false): DiagramBox {
        val middle = Geometry.middle(points)
        return when {
            Geometry.isUprightAtMiddle(points) && otherSide -> DiagramBox(middle.x - LABEL_DISTANCE - width, middle.y - height / 2f, width, height)
            Geometry.isUprightAtMiddle(points) -> DiagramBox(middle.x + LABEL_DISTANCE, middle.y - height / 2f, width, height)
            otherSide -> DiagramBox(middle.x - width / 2f, middle.y + LABEL_DISTANCE, width, height)
            else -> DiagramBox(middle.x - width / 2f, middle.y - LABEL_DISTANCE - height, width, height)
        }
    }
}
