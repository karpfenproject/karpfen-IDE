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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The visual information of a statechart that was arranged by hand. It is what the companion file of a state machine
 * file holds, the file named like the state machine file with `.vis` appended.
 *
 * The text of the state machine stays the source of truth. This layout only says where things are drawn. An entry
 * for something the text does not have is ignored and dropped at the next save. Something the text has and this
 * layout does not is placed automatically.
 *
 * All positions are on the grid of [StatechartMetrics.GRID].
 *
 * @property states the middle of the box of each state without nested states, by the name of the state. A state with
 *   nested states has no entry. Its box follows from the states inside it.
 * @property bars the middle of the bar of each split and join, by the id of the transition.
 * @property waypoints the points a normal transition runs through between its two states, in order, by the id of the
 *   transition. A transition without an entry is a straight line.
 */
public data class VisLayout(
    public val states: Map<String, DiagramPoint> = emptyMap(),
    public val bars: Map<String, DiagramPoint> = emptyMap(),
    public val waypoints: Map<String, List<DiagramPoint>> = emptyMap(),
)

/**
 * The text of a `.vis` file. It is JSON, so other tools can read and write it:
 *
 * ```
 * {
 *   "version": 1,
 *   "states": { "off": { "x": 120, "y": 40 } },
 *   "bars": { },
 *   "transitions": { "NORMAL:off->on#1": { "waypoints": [ { "x": 200, "y": 90 } ] } }
 * }
 * ```
 *
 * Reading is tolerant. An entry that is not a point is left out, and unknown keys are ignored. A text that is no JSON
 * object at all gives null, and the caller falls back to the automatic layout.
 */
public object VisLayoutJson {

    private val json = Json { prettyPrint = true }

    /** The current version of the format. */
    private const val VERSION = 1

    /** The text of the file for [layout]. */
    public fun encode(layout: VisLayout): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("version", VERSION)
            putJsonObject("states") { layout.states.toSortedMap().forEach { (name, point) -> put(name, pointOf(point)) } }
            putJsonObject("bars") { layout.bars.toSortedMap().forEach { (id, point) -> put(id, pointOf(point)) } }
            putJsonObject("transitions") {
                layout.waypoints.toSortedMap().filterValues { it.isNotEmpty() }.forEach { (id, points) ->
                    putJsonObject(id) { put("waypoints", buildJsonArray { points.forEach { add(pointOf(it)) } }) }
                }
            }
        },
    ) + "\n"

    /** The layout that [text] holds, or null if [text] is no JSON object. */
    public fun decode(text: String): VisLayout? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        return VisLayout(
            states = points(root["states"]),
            bars = points(root["bars"]),
            waypoints = (root["transitions"] as? JsonObject).orEmpty()
                .mapValues { (_, value) ->
                    ((value as? JsonObject)?.get("waypoints") as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull(::point)
                }
                .filterValues { it.isNotEmpty() },
        )
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
