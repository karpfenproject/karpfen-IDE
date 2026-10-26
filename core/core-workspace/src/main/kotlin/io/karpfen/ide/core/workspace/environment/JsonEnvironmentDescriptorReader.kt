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
package io.karpfen.ide.core.workspace.environment

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.environment.EnvironmentDescriptor
import io.karpfen.ide.core.document.environment.EnvironmentSchema
import io.karpfen.ide.core.document.environment.ValueShape
import io.karpfen.ide.core.document.environment.shapedValues
import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.document.json.JsonParser
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The [EnvironmentDescriptorReader] in three steps, each only when the one before found no error:
 * 1. the environment language's [JsonParser] reads the text, so a syntax error is reported with the same diagnostics
 *    and ranges as in the editor, as the text's [DescriptorReadResult.syntaxDiagnostics];
 * 2. [DescriptorValidator] checks the tree against `EnvironmentSchema.ROOT`;
 * 3. kotlinx.serialization decodes the text into [EnvironmentDescriptor] (ignoring the unknown keys the validator
 *    warned about). The schema is derived from that class, so the validator accepts only trees this decoding
 *    accepts; a decoding failure is still reported as an ERROR `descriptor-invalid` at the start of the descriptor
 *    instead of escaping.
 *
 * File references are the strings the schema gives a file role ([shapedValues]), in document order.
 */
internal class JsonEnvironmentDescriptorReader : EnvironmentDescriptorReader {

    /** Decodes values only; unknown keys are the validator's findings. */
    private val json = Json { ignoreUnknownKeys = true }

    private val validator = DescriptorValidator(EnvironmentSchema.ROOT)

    override fun read(path: Path, text: String): DescriptorReadResult {
        val document = JsonParser.parse(text)
        val root = document.root
        if (!document.isSyntaxClean || root == null) {
            val syntax = document.diagnostics.sortedWith(Diagnostic.ORDER)
            return DescriptorReadResult(null, emptyList(), emptyList(), syntaxDiagnostics = syntax)
        }
        val findings = validator.check(root)
        if (findings.any { it.severity == Severity.ERROR }) return invalid(findings)
        val descriptor = try {
            json.decodeFromString(EnvironmentDescriptor.serializer(), text)
        } catch (e: SerializationException) {
            return invalid(findings + undecodable(e, root))
        } catch (e: IllegalArgumentException) {
            return invalid(findings + undecodable(e, root))
        }
        return DescriptorReadResult(descriptor, findings.sortedWith(Diagnostic.ORDER), references(root))
    }

    /** The result of a descriptor that could not be decoded: no descriptor, no references, only [diagnostics]. */
    private fun invalid(diagnostics: List<Diagnostic>): DescriptorReadResult =
        DescriptorReadResult(null, diagnostics.sortedWith(Diagnostic.ORDER), emptyList())

    /** The ERROR `descriptor-invalid` at the start of [root] for a [failure] of kotlinx.serialization. */
    private fun undecodable(failure: Exception, root: JsonNode): Diagnostic {
        val reason = failure.message?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: failure.javaClass.simpleName
        return Diagnostic(
            range = TextRange.ofLength(root.range.start, 1),
            severity = Severity.ERROR,
            message = "Invalid environment descriptor: $reason",
            code = WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID,
            source = DiagnosticSource.IDE,
        )
    }

    /** Every file named by the descriptor [root] with the range of its string content, in document order. */
    private fun references(root: JsonNode): List<DescriptorFileReference> =
        shapedValues(root, EnvironmentSchema.ROOT).mapNotNull { value ->
            val role = (value.shape as? ValueShape.FilePath)?.role ?: return@mapNotNull null
            val string = value.node as? JsonNode.StringNode ?: return@mapNotNull null
            DescriptorFileReference(role, string.value, string.content)
        }
}
