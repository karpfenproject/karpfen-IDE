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
import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange

/**
 * The outline of a descriptor's JSON tree. Every member becomes a [SymbolKind.KEY] node named by its key. Its detail
 * is its value when that is a string, a number or a literal, and its selection is the key. Every list item becomes a
 * [SymbolKind.ENTRY] node. Objects and lists nest their members and items.
 *
 * An entry is named by the file it names. That is the first of its strings that the schema expects to be a file path,
 * so a state machine entry is named by its file whatever the order of its keys. An entry that names no file is named by
 * its first string, or by its position such as `[0]` when it has none. The class has no side effects. Use one instance
 * per tree.
 *
 * @param filePaths the ranges of the strings that the schema expects to be file paths.
 */
internal class DescriptorOutline private constructor(private val filePaths: Set<TextRange>) {

    /** The nodes of the members or items of [value]. A string, number or literal has none. */
    private fun childrenOf(value: JsonNode?): List<OutlineNode> = when (value) {
        is JsonNode.ObjectNode -> value.members.map(::keyNode)
        is JsonNode.ArrayNode -> value.items.mapIndexed(::entryNode)
        else -> emptyList()
    }

    /** The KEY node of [member]. */
    private fun keyNode(member: JsonNode.Member): OutlineNode =
        OutlineNode(
            kind = SymbolKind.KEY,
            name = member.key.value,
            detail = member.value?.let(::scalarText),
            range = member.range,
            selectionRange = member.key.content,
            children = childrenOf(member.value),
        )

    /** The ENTRY node of the list item [item] at [index]. */
    private fun entryNode(index: Int, item: JsonNode): OutlineNode {
        val label = labelOf(item)
        return OutlineNode(
            kind = SymbolKind.ENTRY,
            name = (label?.value ?: scalarText(item))?.takeIf { it.isNotBlank() } ?: "[$index]",
            detail = null,
            range = item.range,
            selectionRange = label?.content ?: TextRange.empty(item.range.start),
            children = childrenOf(item),
        )
    }

    /**
     * The string that names an entry. For a string item it is the item itself. For an object item it is the file path,
     * or else the first string.
     */
    private fun labelOf(item: JsonNode): JsonNode.StringNode? = when (item) {
        is JsonNode.StringNode -> item
        is JsonNode.ObjectNode -> {
            val strings = item.members.mapNotNull { it.value as? JsonNode.StringNode }
            strings.firstOrNull { it.range in filePaths } ?: strings.firstOrNull()
        }
        else -> null
    }

    /** The text of a string, number or literal. Null for a container. */
    private fun scalarText(value: JsonNode): String? = when (value) {
        is JsonNode.StringNode -> value.value
        is JsonNode.NumberNode -> value.text
        is JsonNode.LiteralNode -> value.literal.text
        is JsonNode.ObjectNode, is JsonNode.ArrayNode -> null
    }

    companion object {
        /**
         * The top-level outline nodes of the tree whose [shapedValues] are [values], with the root first. There are
         * none without a root.
         */
        fun of(values: List<ShapedValue>): List<OutlineNode> {
            val root = values.firstOrNull()?.node ?: return emptyList()
            val filePaths = values.filter { it.shape is ValueShape.FilePath }.mapTo(HashSet()) { it.node.range }
            return DescriptorOutline(filePaths).childrenOf(root)
        }
    }
}
