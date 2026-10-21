package io.karpfen.ide.core.workspace.environment

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * [EnvironmentDescriptorReader] on kotlinx.serialization JSON (lenient about unknown keys, which become warnings).
 *
 * Steps: kotlinx.serialization validates the JSON syntax (strict RFC 8259); [JsonSourceParser] records positions;
 * [DescriptorSchema] reports unknown keys (WARNING) and missing required keys (ERROR) against the serial descriptor of
 * [EnvironmentDescriptor]; kotlinx.serialization decodes the values (type errors → ERROR `descriptor-invalid` at the
 * offending value where its message names a path or an offset, see [DecodingErrorLocator]). File references come from
 * the position tree.
 */
internal class JsonEnvironmentDescriptorReader : EnvironmentDescriptorReader {

    private val json = Json { ignoreUnknownKeys = true }
    private val shape = EnvironmentDescriptor.serializer().descriptor
    private val schema = DescriptorSchema(shape)

    override fun read(path: Path, text: String): DescriptorReadResult {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return invalid(listOf(invalidDescriptor(e, text, tree = null)))
        }
        val tree = JsonSourceParser(text).parse()
        val keyFindings = tree?.let(schema::check).orEmpty()
        if (keyFindings.any { it.severity == Severity.ERROR }) return invalid(keyFindings)
        val descriptor = try {
            json.decodeFromJsonElement(EnvironmentDescriptor.serializer(), element)
        } catch (e: SerializationException) {
            return invalid(keyFindings + invalidDescriptor(e, text, tree))
        } catch (e: IllegalArgumentException) {
            return invalid(keyFindings + invalidDescriptor(e, text, tree))
        }
        return DescriptorReadResult(descriptor, keyFindings.sortedWith(Diagnostic.ORDER), references(tree))
    }

    private fun invalid(diagnostics: List<Diagnostic>): DescriptorReadResult =
        DescriptorReadResult(null, diagnostics.sortedWith(Diagnostic.ORDER), emptyList())

    private fun invalidDescriptor(failure: Exception, text: String, tree: JsonNode?): Diagnostic {
        val reason = failure.message?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: failure.javaClass.simpleName
        return Diagnostic(
            range = DecodingErrorLocator(text, tree, shape).rangeOf(failure.message.orEmpty()),
            severity = Severity.ERROR,
            message = "Invalid environment descriptor: $reason",
            code = WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID,
            source = DiagnosticSource.IDE,
        )
    }

    /** Every file named by the descriptor with the range of its string value, in document order. */
    private fun references(tree: JsonNode?): List<DescriptorFileReference> {
        val root = tree as? JsonNode.ObjectNode ?: return emptyList()
        val references = ArrayList<DescriptorFileReference>()
        fun add(role: DescriptorFileRole, node: JsonNode?) {
            if (node is JsonNode.StringNode) references += DescriptorFileReference(role, node.value, node.content)
        }
        add(DescriptorFileRole.METAMODEL, root.member(KEY_METAMODEL)?.value)
        add(DescriptorFileRole.EVENTS, root.member(KEY_EVENTS)?.value)
        add(DescriptorFileRole.MODEL, root.member(KEY_MODEL)?.value)
        (root.member(KEY_STATE_MACHINES)?.value as? JsonNode.ArrayNode)?.items?.forEach { entry ->
            add(DescriptorFileRole.STATE_MACHINE, (entry as? JsonNode.ObjectNode)?.member(KEY_FILE)?.value)
        }
        (root.member(KEY_BRIDGES)?.value as? JsonNode.ArrayNode)?.items?.forEach { add(DescriptorFileRole.BRIDGE, it) }
        return references.sortedBy { it.range.start }
    }

    private companion object {
        const val KEY_METAMODEL = "metamodel"
        const val KEY_EVENTS = "events"
        const val KEY_MODEL = "model"
        const val KEY_STATE_MACHINES = "stateMachines"
        const val KEY_FILE = "file"
        const val KEY_BRIDGES = "bridges"
    }
}
