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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The visual information of a diagram that was arranged by hand. It is what the companion file of a model file holds,
 * the file named like the model file with `.vis` appended.
 *
 * The text of the model stays the source of truth. This layout only says where things are drawn. An entry for
 * something the text does not have is ignored and dropped at the next save. Something the text has and this layout
 * does not is placed automatically.
 *
 * All positions are on the grid of [DiagramGrid].
 *
 * @property boxes the middle of each box by its id. In a statechart these are the states without nested states, by
 *   their names. A state with nested states has no entry, because its box follows from the states inside it. In a
 *   class diagram these are the types, and in an object diagram the objects.
 * @property bars the middle of the bar of each split and join of a statechart, by the id of the transition.
 * @property waypoints the points a line runs through between its two boxes, in order, by the id of the line. A line
 *   without an entry is a straight line. In a statechart the lines are the normal transitions, the timeouts and each
 *   line of a split or join between a state and its bar, by the ids that
 *   [io.karpfen.ide.notation.diagram.statechart.StatechartEdge.lineIds] gives.
 * @property labels how far the label of a line was moved away from its usual place beside the middle of the line, by
 *   the id of the line. In a statechart that is the id of the transition or timeout, also for a split or join. So a label that was moved keeps its distance when its line moves.
 * @property frames the box of each state with nested states as the user sized it, by the name of the state. The state
 *   is drawn at least this large, and larger where its nested states need the room.
 */
public data class VisLayout(
    public val boxes: Map<String, DiagramPoint> = emptyMap(),
    public val bars: Map<String, DiagramPoint> = emptyMap(),
    public val waypoints: Map<String, List<DiagramPoint>> = emptyMap(),
    public val labels: Map<String, DiagramPoint> = emptyMap(),
    public val frames: Map<String, DiagramBox> = emptyMap(),
)

/**
 * The text of a `.vis` file. It is JSON, so other tools can read and write it:
 *
 * ```
 * {
 *   "version": 2,
 *   "boxes": { "off": { "x": 120, "y": 40 } },
 *   "bars": { },
 *   "lines": { "NORMAL:off->on#1": { "waypoints": [ { "x": 200, "y": 90 } ], "label": { "x": 20, "y": 0 } } },
 *   "frames": { "on": { "x": 60, "y": 120, "width": 300, "height": 160 } }
 * }
 * ```
 *
 * Reading is tolerant. An entry that is not a point is left out, and unknown keys are ignored. A text that is no JSON
 * object at all gives null, and the caller falls back to the automatic layout. A file that names the boxes `states`
 * and the lines `transitions` is read as well.
 */
public object VisLayoutJson {

    private val json = Json { prettyPrint = true }

    /** The current version of the format. */
    private const val VERSION = 2

    /** The text of the file for [layout]. */
    public fun encode(layout: VisLayout): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("version", VERSION)
            putJsonObject("boxes") { layout.boxes.toSortedMap().forEach { (name, point) -> put(name, pointOf(point)) } }
            putJsonObject("bars") { layout.bars.toSortedMap().forEach { (id, point) -> put(id, pointOf(point)) } }
            putJsonObject("lines") {
                (layout.waypoints.filterValues { it.isNotEmpty() }.keys + layout.labels.keys).toSortedSet().forEach { id ->
                    putJsonObject(id) {
                        layout.waypoints[id]?.takeIf { it.isNotEmpty() }?.let { points ->
                            put("waypoints", buildJsonArray { points.forEach { add(pointOf(it)) } })
                        }
                        layout.labels[id]?.let { put("label", pointOf(it)) }
                    }
                }
            }
            putJsonObject("frames") { layout.frames.toSortedMap().forEach { (name, box) -> put(name, boxOf(box)) } }
        },
    ) + "\n"

    /** The layout that [text] holds, or null if [text] is no JSON object. */
    public fun decode(text: String): VisLayout? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val lines = ((root["lines"] ?: root["transitions"]) as? JsonObject).orEmpty().mapValues { it.value as? JsonObject }
        return VisLayout(
            boxes = points(root["boxes"] ?: root["states"]),
            bars = points(root["bars"]),
            waypoints = lines
                .mapValues { (_, line) -> (line?.get("waypoints") as? JsonArray).orEmpty().mapNotNull(::point) }
                .filterValues { it.isNotEmpty() },
            labels = lines.mapNotNull { (id, line) -> line?.get("label")?.let(::point)?.let { id to it } }.toMap(),
            frames = (root["frames"] as? JsonObject).orEmpty().mapNotNull { (name, value) -> box(value)?.let { name to it } }.toMap(),
        )
    }

    private fun boxOf(box: DiagramBox): JsonObject = buildJsonObject {
        put("x", number(box.x))
        put("y", number(box.y))
        put("width", number(box.width))
        put("height", number(box.height))
    }

    private fun box(element: JsonElement): DiagramBox? {
        val fields = element as? JsonObject ?: return null
        val corner = point(element) ?: return null
        val width = runCatching { fields["width"]?.jsonPrimitive?.floatOrNull }.getOrNull() ?: return null
        val height = runCatching { fields["height"]?.jsonPrimitive?.floatOrNull }.getOrNull() ?: return null
        return DiagramBox(corner.x, corner.y, width, height)
    }

    private fun pointOf(point: DiagramPoint): JsonObject = buildJsonObject {
        put("x", number(point.x))
        put("y", number(point.y))
    }

    /** [value] as a whole number where it is one, so the file reads `120` and not `120.0`. */
    private fun number(value: Float): Number = if (value % 1f == 0f) value.toInt() else value

    private fun points(element: JsonElement?): Map<String, DiagramPoint> =
        (element as? JsonObject).orEmpty().mapNotNull { (key, value) -> point(value)?.let { key to it } }.toMap()

    private fun point(element: JsonElement): DiagramPoint? {
        val fields = element as? JsonObject ?: return null
        val x = runCatching { fields["x"]?.jsonPrimitive?.floatOrNull }.getOrNull() ?: return null
        val y = runCatching { fields["y"]?.jsonPrimitive?.floatOrNull }.getOrNull() ?: return null
        return DiagramPoint(x, y)
    }
}
