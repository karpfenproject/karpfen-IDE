package io.karpfen.ide.core.document.environment

import io.karpfen.ide.core.document.json.JsonLiteral
import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.parse.SemanticSpan
import io.karpfen.ide.core.text.TextRange

/**
 * The semantic spans of a descriptor, from its [shapedValues]: every key ([SemanticKind.PROPERTY_NAME], inside its
 * quotes), the strings the schema gives a role (file paths [SemanticKind.FILE_PATH], object keys
 * [SemanticKind.OBJECT_NAME], event domains [SemanticKind.EVENT_DOMAIN], inside their quotes), every number
 * ([SemanticKind.NUMBER_VALUE]) and every `true` and `false` ([SemanticKind.BOOLEAN_VALUE]). Other strings keep their
 * lexical style. The spans are sorted and disjoint, because keys and values are disjoint tokens; empty ones are left
 * out. Pure.
 */
internal object DescriptorSpans {

    /** The spans of [values]. */
    fun of(values: List<ShapedValue>): List<SemanticSpan> {
        val spans = ArrayList<SemanticSpan>()
        for (value in values) {
            val node = value.node
            if (node is JsonNode.ObjectNode) node.members.forEach { spans.add(it.key.content, SemanticKind.PROPERTY_NAME) }
            when (node) {
                is JsonNode.StringNode -> roleOf(value.shape)?.let { spans.add(node.content, it) }
                is JsonNode.NumberNode -> spans.add(node.range, SemanticKind.NUMBER_VALUE)
                is JsonNode.LiteralNode -> if (node.literal != JsonLiteral.NULL) spans.add(node.range, SemanticKind.BOOLEAN_VALUE)
                is JsonNode.ObjectNode, is JsonNode.ArrayNode -> Unit
            }
        }
        return spans.sortedBy { it.range.start }
    }

    /** The semantic kind of a string of [shape], or null for a string without a role. */
    private fun roleOf(shape: ValueShape?): SemanticKind? = when (shape) {
        is ValueShape.FilePath -> SemanticKind.FILE_PATH
        ValueShape.ObjectKey -> SemanticKind.OBJECT_NAME
        ValueShape.EventDomain -> SemanticKind.EVENT_DOMAIN
        else -> null
    }

    /** Adds the span of [range] unless it is empty. */
    private fun MutableList<SemanticSpan>.add(range: TextRange, kind: SemanticKind) {
        if (!range.isEmpty) this += SemanticSpan(range, kind)
    }
}
