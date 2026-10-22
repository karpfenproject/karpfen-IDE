package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.environment.DescriptorKey
import io.karpfen.ide.core.document.environment.ValueShape
import io.karpfen.ide.core.document.json.JsonDocument
import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.document.json.JsonToken
import io.karpfen.ide.core.document.json.JsonTokenKind
import io.karpfen.ide.core.text.TextRange

/**
 * Finds the [DescriptorSlot] at a caret: it descends from the root into the object or list that holds the caret,
 * carrying the shape the [schema] expects there, until it reaches a key string, a value, or blank space between them.
 *
 * Rules, in the order they are tried inside an object or list:
 * - inside a key string, from after its opening quote to before its closing quote (or the end of an unterminated
 *   string), the caret is on that key;
 * - inside a member's value, or a list item, the caret is on that value (descending into objects and lists);
 * - after the `:` of a member without value, up to the next token, the caret is on that member's value, also within a
 *   bare word typed there;
 * - in blank space or a bare word right after `{` or `,` of an object it is on a new key, after `[` or `,` of a list on
 *   a new item;
 * - anywhere else (between a value and the `,` after it, before the root) on nothing.
 *
 * Containers that the text does not close reach as far as their parent does, the root to the end of the text, so the
 * caret at the end of an unfinished descriptor is still inside it. Works on broken text; pure.
 */
internal class DescriptorCaret(private val schema: ValueShape.Record) {

    /** The slot at [offset] of [document], a text of length [textLength]. */
    fun slotAt(document: JsonDocument, offset: Int, textLength: Int): DescriptorSlot {
        val root = document.root ?: return DescriptorSlot.None
        val walk = Walk(document, root, offset)
        return walk.inValue(root, schema, Place(key = null, owner = null, ownerShape = null), textLength) ?: DescriptorSlot.None
    }

    /** Whose value a node is: the [key] it belongs to and the object [owner] (with [ownerShape]) that holds the key. */
    private data class Place(val key: DescriptorKey?, val owner: JsonNode.ObjectNode?, val ownerShape: ValueShape.Record?)

    /** One search for the slot at [offset] in [document], whose root is [root]. */
    private class Walk(val document: JsonDocument, val root: JsonNode, val offset: Int) {

        /** The slot in or at [node] (expected to have [shape]), or null if the caret is not there. */
        fun inValue(node: JsonNode, shape: ValueShape?, place: Place, limit: Int): DescriptorSlot? {
            val fitting = shape?.takeIf { it.accepts(node) }
            return when (node) {
                is JsonNode.ObjectNode -> if (isInside(node.range, node.isClosed, limit)) {
                    inObject(node, fitting as? ValueShape.Record, innerLimit(node.range, node.isClosed, limit))
                } else {
                    null
                }
                is JsonNode.ArrayNode -> if (isInside(node.range, node.isClosed, limit)) {
                    inList(node, fitting as? ValueShape.ListOf, place, innerLimit(node.range, node.isClosed, limit))
                } else {
                    null
                }
                is JsonNode.StringNode ->
                    if (offset in node.content.start..node.content.end) value(place, shape, node, node.content, inString = true) else null
                is JsonNode.NumberNode, is JsonNode.LiteralNode ->
                    if (node.range.containsInclusive(offset)) value(place, shape, node, node.range, inString = false) else null
            }
        }

        /** The slot in the object [node] (expected to have [shape]); its content reaches to [limit]. */
        private fun inObject(node: JsonNode.ObjectNode, shape: ValueShape.Record?, limit: Int): DescriptorSlot {
            for (member in node.members) {
                val key = member.key
                if (offset in key.content.start..key.content.end) {
                    return DescriptorSlot.Key(node, shape, member, key.content, inString = true)
                }
                val descriptorKey = shape?.key(key.value)
                val place = Place(descriptorKey, node, shape)
                val value = member.value
                val colon = member.colon
                if (value != null) {
                    inValue(value, descriptorKey?.shape, place, limit)?.let { return it }
                } else if (colon != null && offset >= colon.end) {
                    missingValue(colon.end, place, descriptorKey?.shape)?.let { return it }
                }
            }
            val word = bareWordAtCaret()
            val before = document.tokenBefore(word?.range?.start ?: offset)
            val atKey = before != null && before.range.start >= node.range.start &&
                (before.kind == JsonTokenKind.BEGIN_OBJECT || before.kind == JsonTokenKind.VALUE_SEPARATOR)
            return if (atKey) DescriptorSlot.Key(node, shape, null, word?.range ?: TextRange.empty(offset), inString = false) else DescriptorSlot.None
        }

        /** The slot in the list [node] (expected to have [shape]), which is the value of [place]. */
        private fun inList(node: JsonNode.ArrayNode, shape: ValueShape.ListOf?, place: Place, limit: Int): DescriptorSlot {
            val item = shape?.item
            for (element in node.items) inValue(element, item, place, limit)?.let { return it }
            val word = bareWordAtCaret()
            val before = document.tokenBefore(word?.range?.start ?: offset)
            val atItem = before != null && before.range.start >= node.range.start &&
                (before.kind == JsonTokenKind.BEGIN_ARRAY || before.kind == JsonTokenKind.VALUE_SEPARATOR)
            return if (atItem) value(place, item, null, word?.range ?: TextRange.empty(offset), inString = false) else DescriptorSlot.None
        }

        /**
         * The slot of a member without value whose colon ends at [colonEnd]: the caret is on the value up to the next
         * token, or within a bare word right there; null if the caret lies beyond.
         */
        private fun missingValue(colonEnd: Int, place: Place, shape: ValueShape?): DescriptorSlot? {
            val next = document.tokens.firstOrNull { it.range.start >= colonEnd }
            return when {
                next != null && next.kind == JsonTokenKind.INVALID && next.range.containsInclusive(offset) ->
                    value(place, shape, null, next.range, inString = false)
                next == null || offset <= next.range.start -> value(place, shape, null, TextRange.empty(offset), inString = false)
                else -> null
            }
        }

        /** The value slot of [place] if everything about it is known, else [DescriptorSlot.None]. */
        private fun value(place: Place, shape: ValueShape?, node: JsonNode?, range: TextRange, inString: Boolean): DescriptorSlot {
            val key = place.key ?: return DescriptorSlot.None
            val owner = place.owner ?: return DescriptorSlot.None
            val ownerShape = place.ownerShape ?: return DescriptorSlot.None
            return DescriptorSlot.Value(key, shape ?: return DescriptorSlot.None, node, range, inString, owner, ownerShape, root)
        }

        /** The bare word (an INVALID token) containing the caret or ending at it, or null. */
        private fun bareWordAtCaret(): JsonToken? =
            document.tokenAt(offset)?.takeIf { it.kind == JsonTokenKind.INVALID }
                ?: document.tokenBefore(offset)?.takeIf { it.kind == JsonTokenKind.INVALID && it.range.end == offset }

        /** True if the caret is after the opening bracket of a container at [range] and not after its content. */
        private fun isInside(range: TextRange, isClosed: Boolean, limit: Int): Boolean =
            offset > range.start && offset <= innerLimit(range, isClosed, limit)

        /** Where the content of a container ends: before its closing bracket, or at [limit] if it is not closed. */
        private fun innerLimit(range: TextRange, isClosed: Boolean, limit: Int): Int = if (isClosed) range.end - 1 else limit
    }
}
