package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.environment.DescriptorKey
import io.karpfen.ide.core.document.environment.ValueShape
import io.karpfen.ide.core.document.json.JsonStrings
import io.karpfen.ide.core.lang.completion.Snippet

/**
 * The text completion inserts for descriptor keys and values, derived from their [ValueShape]s, so a new key arrives
 * with a value of the right kind: a string is `""` with the caret inside, a whole number its first suggestion, an
 * object `{ … }` with its required keys, a list `[ … ]` with one item. The caret marker ([Snippet.CARET]) goes to the
 * first place to fill. Pure.
 */
internal object ValueTemplates {

    /** The value template of [shape], with the caret marker at its first place to fill. */
    fun of(shape: ValueShape): String = TemplateWriter().apply { value(shape) }.text()

    /** `"key": ` followed by the value template of [key]'s shape. */
    fun member(key: DescriptorKey): String = "\"${JsonStrings.encode(key.name)}\": ${of(key.shape)}"

    /** The label of a value template in the completion list: `{ … }` for an object, `[ … ]` for a list. */
    fun label(shape: ValueShape): String = when (shape) {
        is ValueShape.Record -> "{ … }"
        is ValueShape.ListOf -> "[ … ]"
        else -> shape.description
    }

    /** [value] as a JSON string literal with its quotes. */
    fun quoted(value: String): String = "\"${JsonStrings.encode(value)}\""

    /** Writes one template; the first [hole] gets the caret marker. */
    private class TemplateWriter {
        private val out = StringBuilder()
        private var caretPlaced = false

        fun text(): String = out.toString()

        /** Writes the template of [shape]. */
        fun value(shape: ValueShape) {
            when (shape) {
                is ValueShape.Record -> record(shape)
                is ValueShape.ListOf -> {
                    out.append("[ ")
                    value(shape.item)
                    out.append(" ]")
                }
                is ValueShape.FilePath, ValueShape.ObjectKey, ValueShape.EventDomain, is ValueShape.Text -> {
                    out.append('"')
                    hole()
                    out.append('"')
                }
                is ValueShape.WholeNumber -> {
                    out.append(shape.suggestions.firstOrNull() ?: 0)
                    hole()
                }
                ValueShape.Flag -> {
                    out.append("true")
                    hole()
                }
            }
        }

        /** An object with its required keys, or an empty one with the caret inside. */
        private fun record(shape: ValueShape.Record) {
            val required = shape.keys.filter { it.isRequired }
            out.append("{ ")
            if (required.isEmpty()) hole()
            required.forEachIndexed { index, key ->
                if (index > 0) out.append(", ")
                out.append("\"${JsonStrings.encode(key.name)}\": ")
                value(key.shape)
            }
            out.append(" }")
        }

        /** The caret marker, at the first place to fill only. */
        private fun hole() {
            if (caretPlaced) return
            out.append(Snippet.CARET)
            caretPlaced = true
        }
    }
}
