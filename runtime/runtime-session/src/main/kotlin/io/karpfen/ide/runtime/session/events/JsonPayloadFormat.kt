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
package io.karpfen.ide.runtime.session.events

import io.karpfen.dsl.meta.AssociationType
import io.karpfen.dsl.meta.SimplePropertyType
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.json.JsonLiteral
import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.document.json.JsonParser
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.message.EventPayload
import io.karpfen.ide.runtime.protocol.message.RuntimeJson
import io.karpfen.ide.runtime.session.schema.PropertyShape
import io.karpfen.ide.runtime.session.schema.RelationShape
import io.karpfen.ide.runtime.session.schema.TypeSchema
import io.karpfen.ide.runtime.session.schema.TypeShape

/**
 * JSON payloads. They are checked the way the runtime's `JsonToModelConverter` reads them against the event metamodel,
 * with the event's name as the payload type. That is the converter version the runtime pins, with org.json 20240303.
 *
 * - The payload must be a JSON object without repeated keys. org.json refuses repeated keys, which makes the payload
 *   null.
 * - `__id__` is read as a string at every level. Another value makes the payload null, and a string becomes the
 *   object's id. `__type__` is not read.
 * - Number properties must be JSON numbers and boolean properties must be JSON booleans. Otherwise the whole payload
 *   becomes null. String properties take the text of any value. List properties must be arrays.
 * - Relations must be objects, or arrays of objects for lists, and they become new objects. This holds for `has` and
 *   `knows` alike.
 * - Missing members get defaults. These are `0`, `false`, `""`, empty lists and no object. `null` members are left
 *   out. Unknown keys are ignored.
 *
 * The checks use core-document's JSON parser, which gives every node its range and keeps repeated keys.
 *
 * @param schema the schema of the event metamodel, or null if the deployment has no event definitions.
 */
internal class JsonPayloadFormat(private val schema: TypeSchema?) : PayloadFormat {
    /** The kind this format writes. */
    override val kind: PayloadKind = PayloadKind.JSON

    /**
     * The payload type of [event] with every scalar at its default and every `has` relation nested. Without a payload
     * type the template is `{}`.
     */
    override fun template(event: InjectableEvent): String {
        val type = event.payloadType?.let { schema?.type(it) } ?: return "{}"
        return TemplateWriter().obj(type, depth = 1, indent = "")
    }

    /** The findings on [text] as the JSON payload of [event]. */
    override fun validate(text: String, event: InjectableEvent): PayloadValidation {
        val whole = TextRange(0, text.length)
        if (schema == null) {
            return PayloadValidation(
                listOf(
                    PayloadDiagnosticCodes.finding(
                        PayloadDiagnosticCodes.NOT_PARSED,
                        whole,
                        "The environment has no event definitions, so the runtime never parses payloads.",
                    ),
                ),
            )
        }
        return PayloadValidation(Checker(schema, text, event).check())
    }

    /** [text] as a JSON payload. */
    override fun encode(text: String): EventPayload = EventPayload.Json(text)

    /**
     * Checks one payload. Confined to one call of [validate].
     *
     * @param schema the schema of the event metamodel.
     * @param text the payload.
     * @param event the event it is the payload of.
     */
    private class Checker(private val schema: TypeSchema, private val text: String, private val event: InjectableEvent) {
        /** The findings so far. */
        private val found = ArrayList<Diagnostic>()

        /** The findings on the payload. */
        fun check(): List<Diagnostic> {
            val whole = TextRange(0, text.length)
            if (event.payloadTypeOrigin == PayloadTypeOrigin.DOMAIN_TYPE) {
                add(
                    PayloadDiagnosticCodes.DOMAIN_TYPE,
                    whole,
                    "The events file declares no type '${event.name}', so the runtime parses the payload against the domain type of that name.",
                )
            }
            val document = JsonParser.parse(text)
            if (!document.isSyntaxClean) {
                document.diagnostics.forEach { add(PayloadDiagnosticCodes.JSON_SYNTAX, it.range, it.message) }
                return found
            }
            val root = document.root
            if (root !is JsonNode.ObjectNode) {
                add(
                    PayloadDiagnosticCodes.NOT_OBJECT,
                    root?.range ?: whole,
                    "The payload must be a JSON object; the runtime turns anything else into no payload.",
                )
                return found
            }
            val type = event.payloadType?.let(schema::type)
            if (type == null) {
                add(
                    PayloadDiagnosticCodes.UNTYPED_EVENT,
                    whole,
                    "Neither the events file nor the domain declares a type '${event.name}', so the runtime never parses the payload.",
                )
                return found
            }
            obj(root, type)
            return found
        }

        /** Checks the object [node] against [type]. */
        private fun obj(node: JsonNode.ObjectNode, type: TypeShape) {
            val seen = HashSet<String>()
            node.members.forEach { member ->
                val key = member.key.value
                if (!seen.add(key)) {
                    add(
                        PayloadDiagnosticCodes.DUPLICATE_KEY,
                        member.key.range,
                        "The key '$key' is repeated; the runtime refuses repeated keys and the payload becomes null.",
                    )
                    return@forEach
                }
                val value = member.value ?: return@forEach
                val property = type.property(key)
                val relation = type.relation(key)
                when {
                    key == WireNames.OBJECT_ID_MEMBER -> id(value)
                    key == WireNames.TYPE_MEMBER -> Unit
                    property != null -> property(member, property, value)
                    relation != null -> relation(member, relation, value)
                    else -> add(
                        PayloadDiagnosticCodes.UNKNOWN_KEY,
                        member.key.range,
                        "The type '${type.name}' has no member '$key'; the runtime ignores it.",
                    )
                }
            }
            val brace = TextRange(node.range.start, minOf(node.range.start + 1, node.range.end))
            (type.properties.map { it.key to defaultOf(it) } + type.relations.map { it.key to if (it.isList) "an empty list" else "no object" })
                .filter { (key, _) -> key !in seen }
                .forEach { (key, default) ->
                    add(PayloadDiagnosticCodes.MEMBER_DEFAULTED, brace, "The member '$key' of '${type.name}' is absent; the runtime fills in $default.")
                }
        }

        /** Checks the `__id__` [value]. */
        private fun id(value: JsonNode) {
            if (value is JsonNode.StringNode) {
                add(PayloadDiagnosticCodes.ID_ASSIGNED, value.range, "The payload object gets the id '${value.value}'.")
            } else {
                add(PayloadDiagnosticCodes.ID_NOT_STRING, value.range, "${WireNames.OBJECT_ID_MEMBER} must be a string; another value makes the payload null.")
            }
        }

        /** Checks the [value] of the property [shape] given by [member]. */
        private fun property(member: JsonNode.Member, shape: PropertyShape, value: JsonNode) {
            when {
                isNull(value) -> omitted(member)
                shape.isList && value !is JsonNode.ArrayNode ->
                    add(PayloadDiagnosticCodes.LIST_EXPECTED, value.range, "The list '${shape.key}' must be a JSON array.")
                value is JsonNode.ArrayNode && shape.isList -> value.items.forEach { scalar(shape, it) }
                else -> scalar(shape, value)
            }
        }

        /** Checks the [value] of a scalar of the property [shape]. */
        private fun scalar(shape: PropertyShape, value: JsonNode) {
            when (shape.type) {
                SimplePropertyType.NUMBER -> if (value !is JsonNode.NumberNode) {
                    add(
                        PayloadDiagnosticCodes.NUMBER_EXPECTED,
                        value.range,
                        "'${shape.key}' is a number and must be a JSON number; anything else makes the payload null.",
                    )
                }
                SimplePropertyType.BOOLEAN -> if (!isBoolean(value)) {
                    add(
                        PayloadDiagnosticCodes.BOOLEAN_EXPECTED,
                        value.range,
                        "'${shape.key}' is a boolean and must be true or false; anything else makes the payload null.",
                    )
                }
                SimplePropertyType.STRING -> if (value !is JsonNode.StringNode) {
                    add(PayloadDiagnosticCodes.TEXT_FROM_VALUE, value.range, "'${shape.key}' is a string; the runtime takes the text of this value.")
                }
            }
        }

        /** Checks the [value] of the relation [shape] given by [member]. */
        private fun relation(member: JsonNode.Member, shape: RelationShape, value: JsonNode) {
            if (shape.association == AssociationType.LINK) {
                add(
                    PayloadDiagnosticCodes.KNOWS_CREATES_OBJECT,
                    member.key.range,
                    "'${shape.key}' refers to a '${shape.targetType}', but the runtime builds a new object from it instead of referring to an existing one.",
                )
            }
            val target = schema.type(shape.targetType)
            when {
                isNull(value) -> omitted(member)
                shape.isList && value is JsonNode.ArrayNode -> value.items.forEach { item -> nestedObject(shape, item, target) }
                shape.isList -> add(PayloadDiagnosticCodes.OBJECT_EXPECTED, value.range, "The list '${shape.key}' must be a JSON array of objects.")
                else -> nestedObject(shape, value, target)
            }
        }

        /** Checks [value], an object of the relation [shape], against its [target] type if the schema knows it. */
        private fun nestedObject(shape: RelationShape, value: JsonNode, target: TypeShape?) {
            if (value !is JsonNode.ObjectNode) {
                add(PayloadDiagnosticCodes.OBJECT_EXPECTED, value.range, "'${shape.key}' must be a JSON object of type '${shape.targetType}'.")
                return
            }
            if (target != null) obj(value, target)
        }

        /** Reports that [member] is `null` and left out. */
        private fun omitted(member: JsonNode.Member) {
            add(PayloadDiagnosticCodes.MEMBER_OMITTED, member.range, "'${member.key.value}' is null; the runtime leaves it out.")
        }

        /** Adds the finding [code]. */
        private fun add(code: String, range: TextRange, message: String) {
            found += PayloadDiagnosticCodes.finding(code, range, message)
        }

        /** True if [value] is `null`. */
        private fun isNull(value: JsonNode): Boolean = value is JsonNode.LiteralNode && value.literal == JsonLiteral.NULL

        /** True if [value] is `true` or `false`. */
        private fun isBoolean(value: JsonNode): Boolean = value is JsonNode.LiteralNode && value.literal != JsonLiteral.NULL

        /** The default the runtime fills in for the absent property [shape], in words. */
        private fun defaultOf(shape: PropertyShape): String =
            if (shape.isList) {
                "an empty list"
            } else {
                when (shape.type) {
                    SimplePropertyType.NUMBER -> "0"
                    SimplePropertyType.BOOLEAN -> "false"
                    SimplePropertyType.STRING -> "an empty string"
                }
            }
    }

    /**
     * Writes the template of a payload type, two spaces per level. Confined to one call of [template].
     */
    private inner class TemplateWriter {
        /**
         * The object of [type] at [depth], with its members indented by [indent] plus one level. Only `has` relations
         * are written. A `has` list is written empty. A single nested object is left out when the schema does not know
         * its type or when [depth] reached the default depth limit of the payload form.
         */
        fun obj(type: TypeShape, depth: Int, indent: String): String {
            val inner = "$indent  "
            val members = type.properties.map { "$inner${RuntimeJson.quote(it.key)}: ${defaultJson(it)}" } +
                type.relations.filter { it.association == AssociationType.EMBEDDED }.mapNotNull { relation ->
                    val target = schema?.type(relation.targetType)
                    when {
                        relation.isList -> "$inner${RuntimeJson.quote(relation.key)}: []"
                        target == null || depth >= PayloadForm.DEFAULT_DEPTH_LIMIT -> null
                        else -> "$inner${RuntimeJson.quote(relation.key)}: ${obj(target, depth + 1, inner)}"
                    }
                }
            return if (members.isEmpty()) "{}" else members.joinToString(",\n", "{\n", "\n$indent}")
        }

        /** The JSON of the default of the property [shape]. */
        private fun defaultJson(shape: PropertyShape): String =
            if (shape.isList) {
                "[]"
            } else {
                when (shape.type) {
                    SimplePropertyType.NUMBER -> "0"
                    SimplePropertyType.BOOLEAN -> "false"
                    SimplePropertyType.STRING -> "\"\""
                }
            }
    }
}
