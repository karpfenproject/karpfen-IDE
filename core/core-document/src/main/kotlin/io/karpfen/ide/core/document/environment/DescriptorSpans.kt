package io.karpfen.ide.core.document.environment

import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.parse.SemanticSpan
import io.karpfen.ide.core.text.TextRange

/**
 * The semantic spans of a descriptor, from its [shapedValues]: every key ([SemanticKind.PROPERTY_NAME], inside its
 * quotes), the strings the schema gives a role (file paths [SemanticKind.FILE_PATH], object keys
 * [SemanticKind.OBJECT_NAME], event domains [SemanticKind.EVENT_DOMAIN], inside their quotes). Other strings, the
 * numbers and the literals keep their lexical style, which already tells them apart. The spans are sorted and
 * disjoint, because keys and values are disjoint tokens; empty ones are left out. Pure.
 */
internal object DescriptorSpans {

    /** The spans of [values]. */
    fun of(values: List<ShapedValue>): List<SemanticSpan> {
        val spans = ArrayList<SemanticSpan>()
        for (value in values) {
            val node = value.node
            if (node is JsonNode.ObjectNode) node.members.forEach { spans.add(it.key.content, SemanticKind.PROPERTY_NAME) }
            if (node is JsonNode.StringNode) roleOf(value.shape)?.let { spans.add(node.content, it) }
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
