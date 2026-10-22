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
 * `descriptor-unknown-key` at the key (unknown keys are warnings, never errors), required keys missing from an object
 * become ERRORs `descriptor-invalid` at the object's opening brace, and a known key repeated in one object becomes an
 * ERROR `descriptor-invalid` at the repetition: kotlinx.serialization would silently decode the last value while other
 * JSON readers take the first or fail, so the IDE and the runtime could analyze different files. Values are not
 * checked here (kotlinx.serialization decodes them). Pure.
 */
internal class DescriptorSchema(private val root: SerialDescriptor) {

    /** The key findings of [tree], in document order of the keys and objects visited. */
    fun check(tree: JsonNode): List<Diagnostic> = ArrayList<Diagnostic>().also { visit(tree, root, location = "", it) }

    /** Checks [node] if it is an object or array of the shape [descriptor] expects; [location] names it in messages. */
    private fun visit(node: JsonNode, descriptor: SerialDescriptor, location: String, findings: MutableList<Diagnostic>) {
        when {
            node is JsonNode.ObjectNode && descriptor.kind == StructureKind.CLASS -> visitObject(node, descriptor, location, findings)
            node is JsonNode.ArrayNode && descriptor.kind == StructureKind.LIST -> node.items.forEachIndexed { index, item ->
                visit(item, descriptor.getElementDescriptor(0), "$location[$index]", findings)
            }
        }
    }

    /** Checks the keys of the object [node] against the class [descriptor] and visits the values of known keys. */
    private fun visitObject(node: JsonNode.ObjectNode, descriptor: SerialDescriptor, location: String, findings: MutableList<Diagnostic>) {
        val known = HashSet<String>()
        for (member in node.members) {
            val index = descriptor.getElementIndex(member.key)
            if (index == CompositeDecoder.UNKNOWN_NAME) {
                findings += unknownKey(member, descriptor, location)
            } else {
                if (!known.add(member.key)) findings += repeatedKey(member, location)
                visit(member.value, descriptor.getElementDescriptor(index), path(location, member.key), findings)
            }
        }
        val present = node.members.mapTo(HashSet()) { it.key }
        for (index in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            if (!descriptor.isElementOptional(index) && name !in present) findings += missingKey(node, name, location)
        }
    }

    /** The WARNING `descriptor-unknown-key` at [member]'s key, listing the keys [descriptor] knows. */
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

    /** The ERROR `descriptor-invalid` at the repetition [member] of a known key. */
    private fun repeatedKey(member: JsonNode.Member, location: String): Diagnostic =
        Diagnostic(
            range = member.keyRange,
            severity = Severity.ERROR,
            message = "Key '${member.key}'${where(location)} appears more than once; give each key once.",
            code = WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID,
            source = DiagnosticSource.IDE,
        )

    /** The ERROR `descriptor-invalid` at the opening brace of [node], which lacks the required key [name]. */
    private fun missingKey(node: JsonNode.ObjectNode, name: String, location: String): Diagnostic =
        Diagnostic(
            range = TextRange.ofLength(node.range.start, 1),
            severity = Severity.ERROR,
            message = "Missing required key '$name'${where(location)}.",
            code = WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID,
            source = DiagnosticSource.IDE,
        )

    /** The phrase naming [location] in a message, empty for the root object. */
    private fun where(location: String): String = if (location.isEmpty()) "" else " in '$location'"

    /** The location of the member [key] of the object at [location], like `stateMachines[0].file`. */
    private fun path(location: String, key: String): String = if (location.isEmpty()) key else "$location.$key"
}
