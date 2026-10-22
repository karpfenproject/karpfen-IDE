package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.environment.DescriptorFileRole
import io.karpfen.ide.core.document.environment.DescriptorKey
import io.karpfen.ide.core.document.environment.ValueShape
import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.HoverProvider
import io.karpfen.ide.core.workspace.environment.NamedFile

/**
 * Hover in environment descriptors, by the [DescriptorSlot] at the caret and the [schema]:
 * - on a key: what its value is, whether it is required, and the key's documentation; on an unknown key, the keys known
 *   there;
 * - on a file path: the file's role and the file it resolves to, and whether the workspace has no such file or one
 *   of another language, by the rule the workspace checks the descriptor with ([DescriptorProject.lookUp]);
 * - on an `attachedTo` value: the object and its type (`object turtle: Robot`), or that the model has no such object;
 * - on an event domain: the domain;
 * - on any other value: the documentation of its key.
 *
 * The hover covers the key or the string content without quotes, or the whole number or literal.
 */
internal class EnvironmentHoverProvider(
    private val trees: DescriptorTrees,
    private val caret: DescriptorCaret,
    private val schema: ValueShape.Record,
) : HoverProvider {

    override fun hover(request: FeatureRequest, offset: Int): Hover? {
        val text = request.document.text
        return when (val slot = caret.slotAt(trees.of(text), text.text, offset)) {
            is DescriptorSlot.Key -> slot.member?.let { member ->
                keyText(member.key.value, slot.shape)?.let { Hover(member.key.content, it) }
            }
            is DescriptorSlot.Value -> valueHover(DescriptorProject(request), slot)
            is DescriptorSlot.Root, DescriptorSlot.None -> null
        }
    }

    /** The text of the key [name] of an object of [shape], or null where nothing is known about the object. */
    private fun keyText(name: String, shape: ValueShape.Record?): String? {
        if (shape == null) return null
        val key = shape.key(name) ?: return "Unknown key '$name', ignored. Known keys here: ${shape.keys.joinToString(", ") { it.name }}."
        return documentationOf(key)
    }

    /** The hover of the value under the caret, or null in blank space. */
    private fun valueHover(project: DescriptorProject, slot: DescriptorSlot.Value): Hover? {
        val node = slot.node ?: return null
        val string = node as? JsonNode.StringNode
        val range = string?.content ?: node.range
        val text = when (val shape = slot.shape) {
            is ValueShape.FilePath -> string?.let { fileText(project, shape.role, it.value) }
            ValueShape.ObjectKey -> string?.let { objectText(project, slot, it.value) }
            ValueShape.EventDomain -> string?.let { "event domain ${it.value}" }
            else -> null
        }
        return Hover(range, text ?: documentationOf(slot.key))
    }

    /** The role of a file path and the file it names. */
    private fun fileText(project: DescriptorProject, role: DescriptorFileRole, value: String): String {
        val where = when (val named = project.lookUp(value, role)) {
            NamedFile.NoPath -> return "'$value' is no valid path."
            is NamedFile.Missing -> "${named.path}, which is not in the workspace"
            is NamedFile.WrongLanguage -> "${named.file.path}, which is not a ${named.language.displayName} file"
            is NamedFile.Present -> named.file.path.toString()
        }
        return "${role.description.replaceFirstChar(Char::uppercaseChar)}: $where"
    }

    /** The object an `attachedTo` names, with its type. */
    private fun objectText(project: DescriptorProject, slot: DescriptorSlot.Value, key: String): String {
        val model = project.fileNamed(DescriptorFileRole.MODEL, slot.root, schema) ?: return "The descriptor names no model."
        val modelObject = project.symbols.objectsOf(model).firstOrNull { it.key == key }
            ?: return "The model ${model.fileName} has no object '$key'."
        return "object $key: ${modelObject.typeName.orEmpty()}"
    }

    /** The documentation of [key]: what its value is, whether it is required, and what it means. */
    private fun documentationOf(key: DescriptorKey): String {
        val required = if (key.isRequired) "required" else "optional"
        return "${key.name}: ${key.shape.description}, $required\n${key.documentation}"
    }
}
