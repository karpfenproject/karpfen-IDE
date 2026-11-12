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
 * The line of one link of a laid-out class or object diagram.
 *
 * @property linkId the id of the link the line stands for.
 * @property points the line from its source to its target, corner by corner.
 * @property label where the label of the link is written, or null for a link without one.
 */
public data class LinkRoute(
    public val linkId: String,
    public val points: List<DiagramPoint>,
    public val label: DiagramBox? = null,
)

/**
 * Where the elements of a class or object diagram are drawn.
 *
 * @property boxes the box of each type or object by its id, in the order of the text.
 * @property routes the lines of the links.
 */
public data class StructureLayout(
    public val boxes: Map<String, DiagramBox>,
    public val routes: List<LinkRoute>,
) : DiagramLayout {

    override val bounds: DiagramBox
        get() {
            val all = boxes.values + routes.mapNotNull { it.label } +
                routes.flatMap { route -> route.points.map { DiagramBox(it.x, it.y, 0f, 0f) } }
            return all.reduceOrNull(DiagramBox::union) ?: DiagramBox(0f, 0f, 0f, 0f)
        }

    /** The id of the box drawn at [point], or null. Where boxes overlap, the one drawn last counts, which lies on top. */
    override fun boxAt(point: DiagramPoint): String? = boxes.entries.lastOrNull { point in it.value }?.key

    /** Always null, because a class or object diagram has no bars. */
    override fun barAt(point: DiagramPoint): String? = null

    override fun boxOf(id: String): DiagramBox? = boxes[id]

    override fun labelAt(point: DiagramPoint): String? = routes.firstOrNull { it.label?.contains(point) == true }?.linkId

    /**
     * The index of the row of the box [id] that is drawn at [point], or null if the point is on no row of that box.
     * The rows start below the title.
     */
    public fun rowAt(id: String, point: DiagramPoint): Int? {
        val box = boxes[id]?.takeIf { point in it } ?: return null
        val below = point.y - box.y - StructureMetrics.TITLE_HEIGHT - StructureMetrics.ROWS_PADDING
        return if (below < 0f) null else (below / StructureMetrics.ROW_HEIGHT).toInt()
    }

    /** Always null, because the size of a type or an object follows from its texts. */
    override fun frameAt(point: DiagramPoint, tolerance: Float): FrameGrip? = null

    override fun lineAt(point: DiagramPoint, tolerance: Float): DiagramLine? =
        routes.map { it to Geometry.distanceToLine(point, it.points) }
            .filter { it.second <= tolerance }
            .minByOrNull { it.second }
            ?.first
            ?.let { DiagramLine(it.linkId, it.points) }

    public companion object {
        /** The layout of a diagram without elements. */
        public val EMPTY: StructureLayout = StructureLayout(emptyMap(), emptyList())
    }
}
