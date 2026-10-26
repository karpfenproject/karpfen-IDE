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
import io.karpfen.ide.core.document.json.JsonNode

/**
 * What the caret of an environment descriptor is on, as [DescriptorCaret] finds it. Every place completion can write
 * at carries the [SlotEdit] that keeps the descriptor valid JSON around a proposal.
 */
internal sealed interface DescriptorSlot {

    /**
     * The root of an empty descriptor, a text of blanks only, where the descriptor object goes.
     *
     * @property shape what the schema expects of the root.
     * @property edit how completion writes there.
     */
    data class Root(val shape: ValueShape.Record, val edit: SlotEdit) : DescriptorSlot

    /**
     * A place for a key of the object [owner]: inside a key string, or in blank space or a bare word right after the
     * object's `{` or a `,`, or after its last member when nothing but the closing `}` follows.
     *
     * @property shape what the schema expects of [owner], or null where it knows nothing (below an unknown key).
     * @property member the member whose key string holds the caret, null in blank space or a bare word.
     * @property edit how completion writes there: the key's content (or, for a key that gets more than its name, the
     *   key up to its closing quote), the bare word at the caret, or the empty range at the caret.
     * @property part what completion writes there, so that the member around the key is complete afterwards.
     */
    data class Key(
        val owner: JsonNode.ObjectNode,
        val shape: ValueShape.Record?,
        val member: JsonNode.Member?,
        val edit: SlotEdit,
        val part: KeyPart,
    ) : DescriptorSlot {
        /** The keys [owner] has already, without the one under the caret. */
        val presentKeys: Set<String> get() = owner.members.filter { it !== member }.mapTo(HashSet()) { it.key.value }
    }

    /**
     * A place for a value of [key]: its value, or an item of its list. Strings, numbers and literals hold it where they
     * are; blank space or a bare word holds it right after the key's `:`, after a list's `[` or a `,`, or after the last
     * item of a list when nothing but the closing `]` follows.
     *
     * @property shape what the schema expects there (for a list item, the list's item shape).
     * @property node the value under the caret, null in blank space or a bare word.
     * @property edit how completion writes there: a string's content (or up to its closing quote), a number or
     *   literal, the bare word at the caret, or the empty range at the caret.
     * @property owner the object that holds [key], whose other keys give a value its context (the file of the state
     *   machine an `attachedTo` belongs to); [ownerShape] is what the schema expects of it.
     * @property root the root of the descriptor (the model, the state machines).
     */
    data class Value(
        val key: DescriptorKey,
        val shape: ValueShape,
        val node: JsonNode?,
        val edit: SlotEdit,
        val owner: JsonNode.ObjectNode,
        val ownerShape: ValueShape.Record,
        val root: JsonNode,
    ) : DescriptorSlot

    /** Nowhere a key or a known value goes. */
    data object None : DescriptorSlot
}

/** What completion writes at a key, so that the member around it is complete afterwards. */
internal enum class KeyPart {
    /** Only the name: the key, a key string or a bare word the user forgot to quote, has its `:`. */
    NAME,

    /** The name and the `:`: the key has a value but lacks the `:` in front of it. */
    NAME_AND_COLON,

    /** The whole member, the key with `:` and a value template: a new key, or a key string with neither `:` nor value. */
    MEMBER,
    ;

    /** The parts of keys. */
    companion object {
        /** What completion writes at the key string of [member]. */
        fun of(member: JsonNode.Member): KeyPart = when {
            member.colon == null && member.value == null -> MEMBER
            member.colon == null -> NAME_AND_COLON
            else -> NAME
        }
    }
}
