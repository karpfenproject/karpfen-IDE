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
package io.karpfen.ide.core.document.environment

import io.karpfen.ide.core.document.json.JsonNode

/**
 * A value of a descriptor's JSON tree together with what the schema expects there.
 *
 * @property node the value in the JSON tree, with its range in the descriptor's text.
 * @property shape the shape the schema expects, or null where the schema knows nothing (below an unknown key, or below
 *   a value whose JSON kind does not fit its shape).
 * @property key the key whose value this is or whose list holds it; null for the root.
 * @property isListItem true for an item of a list, false for the value of a key and for the root.
 * @property location the value's place as messages name it: empty for the root, `observe`, `stateMachines[1]`,
 *   `stateMachines[1].file`.
 */
public data class ShapedValue(
    public val node: JsonNode,
    public val shape: ValueShape?,
    public val key: DescriptorKey?,
    public val isListItem: Boolean,
    public val location: String,
)

/**
 * Every value of the tree [root] in document order (a container before what it holds), each with the shape [schema]
 * expects there: the root is checked against [schema], a member's value against the shape of its key, a list item
 * against the item shape of its list. A value whose JSON kind does not fit its shape passes no shape on to its children.
 * The one walk of a descriptor along its schema, shared by the workspace's checks, the semantic highlighting and the
 * language services. Pure.
 */
public fun shapedValues(root: JsonNode, schema: ValueShape.Record = EnvironmentSchema.ROOT): List<ShapedValue> =
    ArrayList<ShapedValue>().also { visit(ShapedValue(root, schema, key = null, isListItem = false, location = ""), it) }

/** Collects [value] and every value below it into [into]. */
private fun visit(value: ShapedValue, into: MutableList<ShapedValue>) {
    into += value
    val shape = value.shape?.takeIf { it.accepts(value.node) }
    when (val node = value.node) {
        is JsonNode.ObjectNode -> node.members.forEach { member ->
            val key = (shape as? ValueShape.Record)?.key(member.key.value)
            member.value?.let { child ->
                visit(ShapedValue(child, key?.shape, key, isListItem = false, location = locationOf(value.location, member.key.value)), into)
            }
        }
        is JsonNode.ArrayNode -> node.items.forEachIndexed { index, item ->
            visit(ShapedValue(item, (shape as? ValueShape.ListOf)?.item, value.key, isListItem = true, "${value.location}[$index]"), into)
        }
        is JsonNode.StringNode, is JsonNode.NumberNode, is JsonNode.LiteralNode -> Unit
    }
}

/** The location of the member [key] of the object at [location]. */
private fun locationOf(location: String, key: String): String = if (location.isEmpty()) key else "$location.$key"
