package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.environment.DescriptorKey
import io.karpfen.ide.core.document.environment.ValueShape
import io.karpfen.ide.core.document.json.JsonStrings

/**
 * The JSON text completion proposes for descriptor keys and values, derived from their [ValueShape]s, so a new key
 * arrives with a value of the right kind: a string is `""` with the caret inside, a whole number its first suggestion,
 * an object `{ … }` with its required keys, a list `[ … ]` with one item. The caret goes to the first place to fill
 * ([Template.caret]). Every proposal is JSON as it stands outside a string; [SlotEdit.fit] adapts it to the caret's
 * place. Pure.
 */
internal object ValueTemplates {

    /** The value template of [shape], with the caret at its first place to fill. */
    fun of(shape: ValueShape): Template = TemplateWriter().apply { value(shape) }.template()

    /** `"key": ` followed by the value template of [key]'s shape, with the caret at the value's first place to fill. */
    fun member(key: DescriptorKey): Template {
        val name = "${quoted(key.name)}: "
        val value = of(key.shape)
        return Template(name + value.text, name.length + value.caret)
    }

    /** `"key":`, the name of [key] with the colon a key needs in front of its value. */
    fun nameAndColon(key: DescriptorKey): Template = Template("${quoted(key.name)}:")

    /** `"key"`, the name of [key] alone. */
    fun name(key: DescriptorKey): Template = Template(quoted(key.name))

    /** [value] as a JSON string with its quotes. */
    fun string(value: String): Template = Template(quoted(value))

    /** The label of a value template in the completion list: `{ … }` for an object, `[ … ]` for a list. */
    fun label(shape: ValueShape): String = when (shape) {
        is ValueShape.Record -> "{ … }"
        is ValueShape.ListOf -> "[ … ]"
        else -> shape.description
    }

    /** [value] as a JSON string literal with its quotes. */
    private fun quoted(value: String): String = "\"${JsonStrings.encode(value)}\""

    /** Writes one template; the first [hole] gets the caret. */
    private class TemplateWriter {
        private val out = StringBuilder()
        private var caret: Int? = null

        /** The template written so far, the caret at its first hole or at its end. */
        fun template(): Template = Template(out.toString(), caret ?: out.length)

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
                out.append(quoted(key.name)).append(": ")
                value(key.shape)
            }
            out.append(" }")
        }

        /** Marks the current end as the place of the caret, at the first place to fill only. */
        private fun hole() {
            if (caret == null) caret = out.length
        }
    }
}
