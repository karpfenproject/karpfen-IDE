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
import io.karpfen.ide.core.document.environment.EnvironmentSchema
import io.karpfen.ide.core.document.environment.ShapedValue
import io.karpfen.ide.core.document.environment.ValueShape
import io.karpfen.ide.core.document.environment.shapedValues
import io.karpfen.ide.core.document.json.JsonLiteral
import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes

/**
 * Checks the JSON tree of a syntactically valid descriptor against [schema], walking it with `shapedValues`:
 * - a value of the wrong kind becomes an ERROR `descriptor-invalid` at the value (`null` is allowed only as the value of
 *   a nullable key, never as a list item); nothing below it is checked;
 * - an unknown key becomes a WARNING `descriptor-unknown-key` at the key, listing the keys known there (unknown keys are
 *   warnings, never errors, so a descriptor of a newer runtime still opens);
 * - a required key missing from an object becomes an ERROR `descriptor-invalid` at the object's opening brace;
 * - a known key repeated in one object becomes an ERROR `descriptor-invalid` at the repetition: kotlinx.serialization
 *   would silently decode the last value while other JSON readers take the first or fail, so the IDE and the runtime
 *   could analyze different files.
 *
 * A tree without errors is one that kotlinx.serialization decodes into `EnvironmentDescriptor`. Pure.
 */
internal class DescriptorValidator(private val schema: ValueShape.Record = EnvironmentSchema.ROOT) {

    /** The findings of [root], in document order of the values visited. */
    fun check(root: JsonNode): List<Diagnostic> {
        val findings = ArrayList<Diagnostic>()
        for (value in shapedValues(root, schema)) {
            val shape = value.shape ?: continue
            if (!fits(value, shape)) {
                findings += wrongKind(value, shape)
                continue
            }
            val node = value.node
            if (node is JsonNode.ObjectNode && shape is ValueShape.Record) checkKeys(node, shape, value.location, findings)
        }
        return findings
    }

    /** True if [value] is written as [shape] requires, or is a `null` its key allows. */
    private fun fits(value: ShapedValue, shape: ValueShape): Boolean =
        shape.accepts(value.node) || (isNull(value.node) && !value.isListItem && value.key?.isNullable == true)

    /** Checks the keys of [node] against the object [shape] at [location]. */
    private fun checkKeys(node: JsonNode.ObjectNode, shape: ValueShape.Record, location: String, findings: MutableList<Diagnostic>) {
        val seen = HashSet<String>()
        for (member in node.members) {
            val name = member.key.value
            when {
                shape.key(name) == null -> findings += unknownKey(member, shape, location)
                !seen.add(name) -> findings += error(member.key.content, "Key '$name'${where(location)} appears more than once; give each key once.")
            }
        }
        shape.keys.filter { it.isRequired && it.name !in seen }.forEach { key ->
            findings += error(TextRange.ofLength(node.range.start, 1), "Missing required key '${key.name}'${where(location)}.")
        }
    }

    /** The WARNING `descriptor-unknown-key` at [member]'s key, listing the keys [shape] knows. */
    private fun unknownKey(member: JsonNode.Member, shape: ValueShape.Record, location: String): Diagnostic =
        Diagnostic(
            range = member.key.content,
            severity = Severity.WARNING,
            message = "Unknown key '${member.key.value}'${where(location)} is ignored; known keys: ${shape.keys.joinToString(", ") { it.name }}.",
            code = WorkspaceDiagnosticCodes.DESCRIPTOR_UNKNOWN_KEY,
            source = DiagnosticSource.IDE,
        )

    /** The ERROR `descriptor-invalid` at [value], which is not written as [shape] requires. */
    private fun wrongKind(value: ShapedValue, shape: ValueShape): Diagnostic {
        val subject = if (value.location.isEmpty()) "The descriptor" else "'${value.location}'"
        return error(value.node.range, "$subject must be ${shape.description}, not ${kindOf(value.node)}.")
    }

    /** What [node] is, in words, for messages. */
    private fun kindOf(node: JsonNode): String = when (node) {
        is JsonNode.ObjectNode -> "an object"
        is JsonNode.ArrayNode -> "a list"
        is JsonNode.StringNode -> "a string"
        is JsonNode.NumberNode -> node.text
        is JsonNode.LiteralNode -> node.literal.text
    }

    private fun isNull(node: JsonNode): Boolean = node is JsonNode.LiteralNode && node.literal == JsonLiteral.NULL

    /** An ERROR `descriptor-invalid` at [range]. */
    private fun error(range: TextRange, message: String): Diagnostic =
        Diagnostic(range, Severity.ERROR, message, WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID, DiagnosticSource.IDE)

    /** The phrase naming [location] in a message, empty for the root object. */
    private fun where(location: String): String = if (location.isEmpty()) "" else " in '$location'"
}
