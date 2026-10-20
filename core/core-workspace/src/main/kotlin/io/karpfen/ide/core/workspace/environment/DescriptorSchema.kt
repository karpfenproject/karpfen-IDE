package io.karpfen.ide.core.workspace.environment

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder

/**
 * Checks the keys of a descriptor's JSON against its serial descriptor ([root], the `EnvironmentDescriptor` shape), so
 * the known keys have one source, the `@Serializable` class: unknown keys anywhere in the tree become WARNINGs
 * `descriptor-unknown-key` at the key (spec §4: unknown keys are not errors), required keys missing from an object
 * become ERRORs `descriptor-invalid` at the object's opening brace. Values are not checked here (kotlinx.serialization
 * decodes them). Pure.
 */
internal class DescriptorSchema(private val root: SerialDescriptor) {

    /** The key findings of [tree], in document order of the keys and objects visited. */
    fun check(tree: JsonNode): List<Diagnostic> = ArrayList<Diagnostic>().also { visit(tree, root, location = "", it) }

    private fun visit(node: JsonNode, descriptor: SerialDescriptor, location: String, findings: MutableList<Diagnostic>) {
        when {
            node is JsonNode.ObjectNode && descriptor.kind == StructureKind.CLASS -> visitObject(node, descriptor, location, findings)
            node is JsonNode.ArrayNode && descriptor.kind == StructureKind.LIST -> node.items.forEachIndexed { index, item ->
                visit(item, descriptor.getElementDescriptor(0), "$location[$index]", findings)
            }
        }
    }

    private fun visitObject(node: JsonNode.ObjectNode, descriptor: SerialDescriptor, location: String, findings: MutableList<Diagnostic>) {
        for (member in node.members) {
            val index = descriptor.getElementIndex(member.key)
            if (index == CompositeDecoder.UNKNOWN_NAME) {
                findings += unknownKey(member, descriptor, location)
            } else {
                visit(member.value, descriptor.getElementDescriptor(index), path(location, member.key), findings)
            }
        }
        val present = node.members.mapTo(HashSet()) { it.key }
        for (index in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            if (!descriptor.isElementOptional(index) && name !in present) findings += missingKey(node, name, location)
        }
    }

    private fun unknownKey(member: JsonNode.Member, descriptor: SerialDescriptor, location: String): Diagnostic {
        val known = (0 until descriptor.elementsCount).joinToString(", ") { descriptor.getElementName(it) }
        return Diagnostic(
            range = member.keyRange,
            severity = Severity.WARNING,
            message = "Unknown key '${member.key}'${where(location)} is ignored; known keys: $known.",
            code = WorkspaceDiagnosticCodes.DESCRIPTOR_UNKNOWN_KEY,
            source = DiagnosticSource.IDE,
        )
    }

    private fun missingKey(node: JsonNode.ObjectNode, name: String, location: String): Diagnostic =
        Diagnostic(
            range = TextRange.ofLength(node.range.start, 1),
            severity = Severity.ERROR,
            message = "Missing required key '$name'${where(location)}.",
            code = WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID,
            source = DiagnosticSource.IDE,
        )

    private fun where(location: String): String = if (location.isEmpty()) "" else " in '$location'"

    private fun path(location: String, key: String): String = if (location.isEmpty()) key else "$location.$key"
}
