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
package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.environment.DescriptorKey
import io.karpfen.ide.core.document.environment.ValueShape
import io.karpfen.ide.core.document.json.JsonDocument
import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.document.json.JsonToken
import io.karpfen.ide.core.document.json.JsonTokenKind
import io.karpfen.ide.core.text.TextRange

/**
 * Finds the [DescriptorSlot] at a caret. It descends from the root into the object or list that holds the caret and
 * carries along the shape the [schema] expects there. It stops at a key string, a value, or blank space between them.
 *
 * Inside an object or list these rules are tried in order.
 * - Inside a key string the caret is on that key. This runs from after the opening quote to before the closing quote,
 *   or to the end of an unterminated string.
 * - Inside the value of a member, or inside a list item, the caret is on that value. Objects and lists are descended
 *   into.
 * - After the `:` of a member without a value, up to the next token, the caret is on the value of that member. This
 *   also holds within a bare word typed there.
 * - In blank space or a bare word right after `{` or `,` of an object, the caret is on a new key. After `[` or `,` of
 *   a list it is on a new item. A bare word with a `:` behind it is a key the user forgot to quote. The parser left
 *   its `:` and value out of the tree together with the word. So only the name is written there, as [KeyPart.NAME]
 *   says, and the `:` and value stay.
 * - After the last complete member of an object or the last item of a list, when only the closing bracket or the end
 *   of the text follows the caret, the caret is on a new key or item. That one needs a `,` in front, which
 *   [SlotEdit.leading] provides. So a key can be added at the end without typing the comma first.
 * - Anywhere else the caret is on nothing. Examples are the space between a value and the `,` after it, and the space
 *   before the root.
 *
 * In a text of blanks only, which holds no root, the caret is on the [DescriptorSlot.Root]. Containers that the text
 * does not close reach as far as their parent does, and the root reaches to the end of the text. So the caret at the
 * end of an unfinished descriptor is still inside it. How completion writes at the slot is the job of [SlotEdits]. The
 * class works on broken text and has no side effects.
 */
internal class DescriptorCaret(private val schema: ValueShape.Record) {

    /** The slot at [offset] of [text], whose JSON tree is [document]. */
    fun slotAt(document: JsonDocument, text: String, offset: Int): DescriptorSlot {
        val edits = SlotEdits(document, text, offset)
        val root = document.root ?: return if (document.tokens.isEmpty()) {
            DescriptorSlot.Root(schema, edits.outside(TextRange.empty(offset), container = null))
        } else {
            DescriptorSlot.None
        }
        val walk = Walk(document, root, offset, edits)
        val place = Place(key = null, owner = null, ownerShape = null, container = null)
        return walk.inValue(root, schema, place, text.length) ?: DescriptorSlot.None
    }

    /**
     * Whose value a node is. [key] is the key it belongs to. [owner] is the object that holds the key, and [ownerShape]
     * is what the schema expects of that object. [container] is what the node is a member value or item of. It is null
     * for the root.
     */
    private data class Place(
        val key: DescriptorKey?,
        val owner: JsonNode.ObjectNode?,
        val ownerShape: ValueShape.Record?,
        val container: Container?,
    )

    /**
     * One search for the slot at [offset] in [document], whose root is [root]. [edits] works out the edit of the slot.
     */
    private class Walk(val document: JsonDocument, val root: JsonNode, val offset: Int, val edits: SlotEdits) {

        /** The slot in or at [node], which should have [shape], or null if the caret is not there. */
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
                is JsonNode.StringNode -> if (offset in node.content.start..node.content.end) {
                    value(place, shape, node, edits.inString(node, place.container, continues = false))
                } else {
                    null
                }
                is JsonNode.NumberNode, is JsonNode.LiteralNode ->
                    if (node.range.containsInclusive(offset)) value(place, shape, node, edits.on(node, place.container)) else null
            }
        }

        /** The slot in the object [node], which should have [shape]. Its content reaches to [limit]. */
        private fun inObject(node: JsonNode.ObjectNode, shape: ValueShape.Record?, limit: Int): DescriptorSlot {
            for (member in node.members) {
                val key = member.key
                if (offset in key.content.start..key.content.end) return keyOf(node, shape, member)
                val descriptorKey = shape?.key(key.value)
                val place = Place(descriptorKey, node, shape, Container.OBJECT)
                val value = member.value
                val colon = member.colon
                if (value != null) {
                    inValue(value, descriptorKey?.shape, place, limit)?.let { return it }
                } else if (colon != null && offset >= colon.end) {
                    missingValue(colon.end, place, descriptorKey?.shape)?.let { return it }
                }
            }
            val (before, range) = gap()
            return when {
                opens(before, node.range, JsonTokenKind.BEGIN_OBJECT) -> newKey(node, shape, range)
                isAfterLast(node.members.lastOrNull()?.value, node.range, node.isClosed, before, range.end) ->
                    DescriptorSlot.Key(node, shape, null, edits.outside(range, Container.OBJECT, leading = ", "), KeyPart.MEMBER)
                else -> DescriptorSlot.None
            }
        }

        /**
         * The slot of a new key of the object [owner], which should have [shape]. [range] is blank space or a bare word
         * right after the `{` of the object or after a `,`. Completion writes the whole member there. Where a `:`
         * follows the bare word, it writes only the quoted name, so the `:` and the value the user wrote behind it
         * stay.
         */
        private fun newKey(owner: JsonNode.ObjectNode, shape: ValueShape.Record?, range: TextRange): DescriptorSlot.Key {
            val next = document.tokens.firstOrNull { it.range.start >= range.end }
            return if (!range.isEmpty && next?.kind == JsonTokenKind.NAME_SEPARATOR) {
                DescriptorSlot.Key(owner, shape, null, edits.outside(range, container = null), KeyPart.NAME)
            } else {
                DescriptorSlot.Key(owner, shape, null, edits.outside(range, Container.OBJECT), KeyPart.MEMBER)
            }
        }

        /**
         * The slot on the key string of [member] of the object [owner], which should have [shape]. The [KeyPart] that
         * completion writes there decides whether the edit ends at the key and whether a `,` may be missing behind it.
         */
        private fun keyOf(owner: JsonNode.ObjectNode, shape: ValueShape.Record?, member: JsonNode.Member): DescriptorSlot.Key {
            val part = KeyPart.of(member)
            val container = if (part == KeyPart.MEMBER) Container.OBJECT else null
            return DescriptorSlot.Key(owner, shape, member, edits.inString(member.key, container, continues = part != KeyPart.NAME), part)
        }

        /** The slot in the list [node], which should have [shape] and is the value of [place]. */
        private fun inList(node: JsonNode.ArrayNode, shape: ValueShape.ListOf?, place: Place, limit: Int): DescriptorSlot {
            val item = shape?.item
            val itemPlace = place.copy(container = Container.LIST)
            for (element in node.items) inValue(element, item, itemPlace, limit)?.let { return it }
            val (before, range) = gap()
            return when {
                opens(before, node.range, JsonTokenKind.BEGIN_ARRAY) ->
                    value(itemPlace, item, null, edits.outside(range, Container.LIST))
                isAfterLast(node.items.lastOrNull(), node.range, node.isClosed, before, range.end) ->
                    value(itemPlace, item, null, edits.outside(range, Container.LIST, leading = ", "))
                else -> DescriptorSlot.None
            }
        }

        /**
         * The place of the caret between the tokens of a container. It is the token before the caret, paired with the
         * range a proposal replaces there. That range is the bare word at the caret or the empty range at the caret.
         */
        private fun gap(): Pair<JsonToken?, TextRange> {
            val word = bareWordAtCaret()
            return document.tokenBefore(word?.range?.start ?: offset) to (word?.range ?: TextRange.empty(offset))
        }

        /**
         * True if [before], the token before the caret, is the [open] bracket of the container at [range] or a `,` in
         * it.
         */
        private fun opens(before: JsonToken?, range: TextRange, open: JsonTokenKind): Boolean =
            before != null && before.range.start >= range.start &&
                (before.kind == open || before.kind == JsonTokenKind.VALUE_SEPARATOR)

        /**
         * True if [before], the token before the caret, ends [last]. [last] is the last member value or item of the
         * container at [range]. In addition, only the closing bracket of the container or the end of the text may
         * follow [from].
         */
        private fun isAfterLast(last: JsonNode?, range: TextRange, isClosed: Boolean, before: JsonToken?, from: Int): Boolean {
            if (last == null || before == null || before.range.end != last.range.end) return false
            val next = document.tokens.firstOrNull { it.range.start >= from }
            return next == null || (isClosed && next.range.end == range.end)
        }

        /**
         * The slot of a member without a value whose colon ends at [colonEnd]. The caret is on the value up to the next
         * token, or within a bare word right there. It is null if the caret lies beyond.
         */
        private fun missingValue(colonEnd: Int, place: Place, shape: ValueShape?): DescriptorSlot? {
            val next = document.tokens.firstOrNull { it.range.start >= colonEnd }
            return when {
                next != null && next.kind == JsonTokenKind.INVALID && next.range.containsInclusive(offset) ->
                    value(place, shape, null, edits.outside(next.range, place.container))
                next == null || offset <= next.range.start ->
                    value(place, shape, null, edits.outside(TextRange.empty(offset), place.container))
                else -> null
            }
        }

        /** The value slot of [place] if everything about it is known, else [DescriptorSlot.None]. */
        private fun value(place: Place, shape: ValueShape?, node: JsonNode?, edit: SlotEdit): DescriptorSlot {
            val key = place.key ?: return DescriptorSlot.None
            val owner = place.owner ?: return DescriptorSlot.None
            val ownerShape = place.ownerShape ?: return DescriptorSlot.None
            return DescriptorSlot.Value(key, shape ?: return DescriptorSlot.None, node, edit, owner, ownerShape, root)
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
