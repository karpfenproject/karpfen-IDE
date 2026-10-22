package io.karpfen.ide.core.workspace.environment

import io.karpfen.ide.core.text.TextRange
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder

/**
 * The best-effort range of a kotlinx.serialization decoding error in a descriptor [text] with its position
 * [tree] (null if the scanner could not read the text): the offset the error message names, else the end of a
 * truncated document, else the value at the JSON path it names, else the first character of the document.
 *
 * kotlinx' tree decoder names only the path inside the nested value it was decoding (`$.file` for
 * `stateMachines[1].file`, `$.0` for `bridges[0]`). A path is therefore resolved from the root if it can be; otherwise
 * every value whose path ends with it is a candidate, and the first one in document order whose JSON kind does not fit
 * the [schema] (the decoder failed on a value of the wrong kind) wins, else the first one. Pure.
 */
internal class DecodingErrorLocator(
    private val text: String,
    private val tree: JsonNode?,
    private val schema: SerialDescriptor,
) {

    /** The range of the error described by [message]. */
    fun rangeOf(message: String): TextRange =
        offsetIn(message) ?: endOfInput(message) ?: pathIn(message) ?: documentStart()

    /** A truncated document: the empty range after its last non-blank character. */
    private fun endOfInput(message: String): TextRange? =
        if (END_OF_INPUT in message) TextRange.empty(text.trimEnd().length) else null

    /** The character at the offset [message] names (clamped to the text), or null if it names none. */
    private fun offsetIn(message: String): TextRange? {
        val offset = OFFSET.find(message)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val start = offset.coerceAtMost(text.length)
        return TextRange(start, (start + 1).coerceAtMost(text.length))
    }

    /** The value at the JSON path [message] names, resolved as the class documentation describes, or null. */
    private fun pathIn(message: String): TextRange? {
        val root = tree ?: return null
        val path = PATH.find(message)?.groupValues?.get(1) ?: return null
        val segments = SEGMENT.findAll(path).map { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }.toList()
        val values = ArrayList<LocatedValue>().also { locate(root, schema, emptyList(), it) }
        values.firstOrNull { it.path == segments }?.let { return it.node.range }
        val candidates = values.filter { it.path.size >= segments.size && it.path.takeLast(segments.size) == segments }
        return (candidates.firstOrNull { !fits(it) } ?: candidates.firstOrNull())?.node?.range
    }

    /** The first character of the document, or the empty range of a blank one. */
    private fun documentStart(): TextRange {
        val start = tree?.range?.start ?: text.indexOfFirst { !it.isWhitespace() }
        return if (start < 0) TextRange.EMPTY else TextRange.ofLength(start, 1)
    }

    /** Collects [node] and every value below it in document order, with their paths and expected [descriptor]s. */
    private fun locate(node: JsonNode, descriptor: SerialDescriptor?, path: List<String>, into: MutableList<LocatedValue>) {
        into += LocatedValue(path, node, descriptor)
        when (node) {
            is JsonNode.ObjectNode -> node.members.forEach { member ->
                locate(member.value, descriptor?.memberDescriptor(member.key), path + member.key, into)
            }
            is JsonNode.ArrayNode -> node.items.forEachIndexed { index, item ->
                locate(item, descriptor?.itemDescriptor(), path + index.toString(), into)
            }
            is JsonNode.StringNode, is JsonNode.ScalarNode -> Unit
        }
    }

    /** The descriptor of the member [key] of a class or map, or null if unknown. */
    private fun SerialDescriptor.memberDescriptor(key: String): SerialDescriptor? = when (kind) {
        StructureKind.CLASS -> getElementIndex(key).takeIf { it != CompositeDecoder.UNKNOWN_NAME }?.let(::getElementDescriptor)
        StructureKind.MAP -> getElementDescriptor(1)
        else -> null
    }

    /** The descriptor of a list's items, or null for anything else. */
    private fun SerialDescriptor.itemDescriptor(): SerialDescriptor? = if (kind == StructureKind.LIST) getElementDescriptor(0) else null

    /** True if the JSON kind of the value fits the kind its descriptor expects (or nothing is known about it). */
    private fun fits(value: LocatedValue): Boolean {
        val descriptor = value.descriptor ?: return true
        val node = value.node
        if (descriptor.isNullable && node is JsonNode.ScalarNode && text.substring(node.range.start, node.range.end) == "null") return true
        return when (descriptor.kind) {
            PrimitiveKind.STRING, SerialKind.ENUM -> node is JsonNode.StringNode
            is PrimitiveKind -> node is JsonNode.ScalarNode
            StructureKind.LIST -> node is JsonNode.ArrayNode
            StructureKind.CLASS, StructureKind.MAP, StructureKind.OBJECT -> node is JsonNode.ObjectNode
            else -> true
        }
    }

    /** A value of the tree with its [path] of keys and indices from the root and the descriptor it is decoded with. */
    private class LocatedValue(val path: List<String>, val node: JsonNode, val descriptor: SerialDescriptor?)

    private companion object {
        /** The phrase of a message about a truncated document. */
        const val END_OF_INPUT = "end of the input"

        /** The offset a message names. */
        val OFFSET = Regex("""at offset (-?\d+)""")

        /** The JSON path a message names. */
        val PATH = Regex("""at path:? (\$\S*)""")

        /** A path segment: `.key` (group 1) or `[index]` (group 2); the tree decoder writes array items as `.0`. */
        val SEGMENT = Regex("""\.([^.\[]+)|\[(\d+)]""")
    }
}
