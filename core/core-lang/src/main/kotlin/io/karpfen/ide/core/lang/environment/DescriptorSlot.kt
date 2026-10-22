package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.environment.DescriptorKey
import io.karpfen.ide.core.document.environment.ValueShape
import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.text.TextRange

/** What the caret of an environment descriptor is on, as [DescriptorCaret] finds it. */
internal sealed interface DescriptorSlot {

    /**
     * A place for a key of the object [owner]: inside a key string, or in blank space or a bare word right after the
     * object's `{` or a `,`.
     *
     * @property shape what the schema expects of [owner], or null where it knows nothing (below an unknown key).
     * @property member the member whose key string holds the caret, null in blank space.
     * @property replaceRange what completion replaces: the key's content, the bare word at the caret, or the empty range
     *   at the caret.
     * @property inString true inside the key's quotes.
     */
    data class Key(
        val owner: JsonNode.ObjectNode,
        val shape: ValueShape.Record?,
        val member: JsonNode.Member?,
        val replaceRange: TextRange,
        val inString: Boolean,
    ) : DescriptorSlot {
        /** The keys [owner] has already, without the one under the caret. */
        val presentKeys: Set<String> get() = owner.members.filter { it !== member }.mapTo(HashSet()) { it.key.value }
    }

    /**
     * A place for a value of [key]: its value, or an item of its list. Strings, numbers and literals hold it where they
     * are; blank space or a bare word holds it right after the key's `:` or after a list's `[` or a `,`.
     *
     * @property shape what the schema expects there (for a list item, the list's item shape).
     * @property node the value under the caret, null in blank space or a bare word.
     * @property replaceRange what completion replaces: a string's content, a number or literal, the bare word at the
     *   caret, or the empty range at the caret.
     * @property inString true inside a string's quotes.
     * @property owner the object that holds [key], whose other keys give a value its context (the file of the state
     *   machine an `attachedTo` belongs to); [ownerShape] is what the schema expects of it.
     * @property root the root of the descriptor (the model, the state machines).
     */
    data class Value(
        val key: DescriptorKey,
        val shape: ValueShape,
        val node: JsonNode?,
        val replaceRange: TextRange,
        val inString: Boolean,
        val owner: JsonNode.ObjectNode,
        val ownerShape: ValueShape.Record,
        val root: JsonNode,
    ) : DescriptorSlot

    /** Nowhere a key or a known value goes. */
    data object None : DescriptorSlot
}
