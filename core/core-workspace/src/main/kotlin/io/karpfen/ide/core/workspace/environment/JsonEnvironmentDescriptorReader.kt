package io.karpfen.ide.core.workspace.environment

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange
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
 * offending value where its message names a path or an offset). File references come from the position tree.
 */
internal class JsonEnvironmentDescriptorReader : EnvironmentDescriptorReader {

    private val json = Json { ignoreUnknownKeys = true }
    private val schema = DescriptorSchema(EnvironmentDescriptor.serializer().descriptor)

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
            range = ErrorLocation(text, tree).of(failure.message.orEmpty()),
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

    /**
     * Best-effort range of a kotlinx.serialization error in [text]: the offset its message names, else the value at the
     * JSON path it names (`$.stateMachines[0].file`), else the first character of the document.
     */
    private class ErrorLocation(private val text: String, private val tree: JsonNode?) {

        fun of(message: String): TextRange =
            offsetIn(message) ?: pathIn(message) ?: documentStart()

        private fun offsetIn(message: String): TextRange? {
            val offset = OFFSET.find(message)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val start = offset.coerceAtMost(text.length)
            return TextRange(start, (start + 1).coerceAtMost(text.length))
        }

        private fun pathIn(message: String): TextRange? {
            val path = PATH.find(message)?.groupValues?.get(1) ?: return null
            var node: JsonNode = tree ?: return null
            for (segment in SEGMENT.findAll(path)) {
                val (key, index) = segment.destructured
                val child = when {
                    key.isNotEmpty() -> (node as? JsonNode.ObjectNode)?.member(key)?.value
                    else -> (node as? JsonNode.ArrayNode)?.items?.getOrNull(index.toInt())
                }
                node = child ?: break
            }
            return node.range
        }

        private fun documentStart(): TextRange {
            val start = tree?.range?.start ?: text.indexOfFirst { !it.isWhitespace() }
            return if (start < 0) TextRange.EMPTY else TextRange.ofLength(start, 1)
        }
    }

    private companion object {
        const val KEY_METAMODEL = "metamodel"
        const val KEY_EVENTS = "events"
        const val KEY_MODEL = "model"
        const val KEY_STATE_MACHINES = "stateMachines"
        const val KEY_FILE = "file"
        const val KEY_BRIDGES = "bridges"

        val OFFSET = Regex("""at offset (-?\d+)""")
        val PATH = Regex("""at path:? (\$\S*)""")
        val SEGMENT = Regex("""\.([^.\[]+)|\[(\d+)]""")
    }
}
