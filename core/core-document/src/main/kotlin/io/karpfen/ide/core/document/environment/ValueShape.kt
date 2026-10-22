package io.karpfen.ide.core.document.environment

import io.karpfen.ide.core.document.json.JsonNode

/**
 * What the value of a descriptor key looks like: its JSON kind and, for strings, what they name. The shapes of
 * [EnvironmentSchema] drive the descriptor's checks in the workspace, its semantic highlighting and its completion and
 * hover, so all of them agree on every key. There is a shape for each kind of value a key of [EnvironmentDescriptor]
 * has, and no other: a kind such as a boolean gets its shape together with the first key that needs it.
 */
public sealed interface ValueShape {

    /** How a value of this shape is written, for messages ("a string", "a whole number"). */
    public val description: String

    /** True if [node] is written as this shape requires; `null` is no value of any shape (see [DescriptorKey.isNullable]). */
    public fun accepts(node: JsonNode): Boolean

    /**
     * An object with the given [keys].
     *
     * @throws IllegalArgumentException if two keys have the same name.
     */
    public data class Record(public val keys: List<DescriptorKey>) : ValueShape {
        init {
            require(keys.map { it.name }.toSet().size == keys.size) { "duplicate keys in ${keys.map { it.name }}" }
        }

        override val description: String get() = "an object"

        override fun accepts(node: JsonNode): Boolean = node is JsonNode.ObjectNode

        /** The key named [name], or null if the object knows no such key. */
        public fun key(name: String): DescriptorKey? = keys.firstOrNull { it.name == name }
    }

    /** A list whose items have the shape [item]. */
    public data class ListOf(public val item: ValueShape) : ValueShape {
        override val description: String get() = "a list"

        override fun accepts(node: JsonNode): Boolean = node is JsonNode.ArrayNode
    }

    /** A string that names a file of [role], relative to the descriptor's directory. */
    public data class FilePath(public val role: DescriptorFileRole) : ValueShape {
        override val description: String get() = "a file path"

        override fun accepts(node: JsonNode): Boolean = node is JsonNode.StringNode
    }

    /** A string that names an object of the descriptor's model by its key. */
    public data object ObjectKey : ValueShape {
        override val description: String get() = "an object key"

        override fun accepts(node: JsonNode): Boolean = node is JsonNode.StringNode
    }

    /** A string that names an event domain, the first argument of `EVENT("domain", "name")` in a state machine. */
    public data object EventDomain : ValueShape {
        override val description: String get() = "an event domain"

        override fun accepts(node: JsonNode): Boolean = node is JsonNode.StringNode
    }

    /** Any other string; [suggestions] are typical values that completion offers. */
    public data class Text(public val suggestions: List<String> = emptyList()) : ValueShape {
        override val description: String get() = "a string"

        override fun accepts(node: JsonNode): Boolean = node is JsonNode.StringNode
    }

    /**
     * A whole number (no fraction, no exponent, within the range of a 64-bit integer, as the runtime reads it);
     * [suggestions] are typical values that completion offers.
     */
    public data class WholeNumber(public val suggestions: List<Long> = emptyList()) : ValueShape {
        override val description: String get() = "a whole number"

        override fun accepts(node: JsonNode): Boolean = node is JsonNode.NumberNode && node.wholeNumber != null
    }
}

/**
 * A key of an environment descriptor object.
 *
 * @property name the key as written in the JSON text.
 * @property shape what its value looks like.
 * @property isRequired true if every object of its kind must have it.
 * @property isNullable true if `null` is allowed as its value, which then means the same as leaving the key out.
 * @property documentation what the key means, shown by hover and completion.
 */
public data class DescriptorKey(
    public val name: String,
    public val shape: ValueShape,
    public val isRequired: Boolean,
    public val isNullable: Boolean,
    public val documentation: String,
)
