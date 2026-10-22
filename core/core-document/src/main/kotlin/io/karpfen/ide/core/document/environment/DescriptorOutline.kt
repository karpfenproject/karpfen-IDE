package io.karpfen.ide.core.document.environment

import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange

/**
 * The outline of a descriptor's JSON tree: every member is a [SymbolKind.KEY] node named by its key (detail: its value
 * when that is a string, a number or a literal; selection: the key), every list item an [SymbolKind.ENTRY] node, and
 * objects and lists nest their members and items. An entry is named by its first string, which for a state machine
 * entry is its file, or by its position (`[0]`) when it has none. Pure.
 */
internal object DescriptorOutline {

    /** The top-level outline nodes of the tree [root]. */
    fun of(root: JsonNode): List<OutlineNode> = childrenOf(root)

    /** The nodes of the members or items of [value]; none for a string, number or literal. */
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

    /** The string that names an entry: the item itself, or the first string value of an object item. */
    private fun labelOf(item: JsonNode): JsonNode.StringNode? = when (item) {
        is JsonNode.StringNode -> item
        is JsonNode.ObjectNode -> item.members.firstNotNullOfOrNull { it.value as? JsonNode.StringNode }
        else -> null
    }

    /** The text of a string, number or literal; null for a container. */
    private fun scalarText(value: JsonNode): String? = when (value) {
        is JsonNode.StringNode -> value.value
        is JsonNode.NumberNode -> value.text
        is JsonNode.LiteralNode -> value.literal.text
        is JsonNode.ObjectNode, is JsonNode.ArrayNode -> null
    }
}
