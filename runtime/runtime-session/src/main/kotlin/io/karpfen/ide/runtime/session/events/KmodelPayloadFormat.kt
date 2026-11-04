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
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.meta.SimplePropertyType
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.parse.DocumentParser
import io.karpfen.ide.core.document.parse.SemanticMode
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.runtime.protocol.message.EventPayload
import io.karpfen.ide.runtime.session.schema.TypeSchema
import io.karpfen.ide.runtime.session.schema.TypeShape
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * KModel payloads, checked as the runtime's `KmodelDSLConverter.parseSingleObject` reads them: the snippet is parsed as
 * a model against the event metamodel, and its first root object is the payload, of its own type, which the runtime
 * does not compare with the event's name.
 *
 * The snippet is first parsed syntax-only with core-document's KModel parser; only syntax-clean text is read with
 * dsl-tools against the event metamodel. A KModel text holds exactly one root object, in the IDE's grammar and in the
 * runtime's; the runtime's parser recovers from further root objects after the first and takes the first. A snippet
 * that is nothing but root objects one after the other is therefore checked as its first root object, with a warning
 * on the others, and a blank snippet holds no object at all. The event metamodel is read by this format itself from the
 * uploaded texts, the events file with the domain metamodel's types as base types, as the runtime builds it: lazily on
 * the first check, and confined behind a lock together with every read against it, so no dsl-tools object leaves the
 * format.
 *
 * @param sources the texts the event metamodel is read from.
 * @param schema the schema of the event metamodel, for the template; null if the deployment has no event definitions.
 * @param parser the syntax-only KModel parser.
 * @param reader the dsl-tools reader adapter.
 */
internal class KmodelPayloadFormat(
    private val sources: EventSources,
    private val schema: TypeSchema?,
    private val parser: DocumentParser,
    private val reader: DslReader,
) : PayloadFormat {
    /** The kind this format writes. */
    override val kind: PayloadKind = PayloadKind.KMODEL

    /** Guards the event metamodel and every read against it. */
    private val lock = ReentrantLock()

    /** The event metamodel once read; guarded by [lock]. */
    private var eventMetamodel: MetamodelRead? = null

    /** The payload type of [event] as one object with every atomic property at its default and every `has` relation nested. */
    override fun template(event: InjectableEvent): String {
        val typeName = event.payloadType ?: event.name
        val type = schema?.type(typeName)
        return TemplateWriter().obj(PAYLOAD_ID, typeName, type, depth = 1, indent = "")
    }

    /** The findings on [text] as the KModel payload of [event]. */
    override fun validate(text: String, event: InjectableEvent): PayloadValidation {
        val whole = TextRange(0, text.length)
        if (sources.events == null) {
            return single(PayloadDiagnosticCodes.NOT_PARSED, whole, "The environment has no event definitions, so the runtime never parses payloads.")
        }
        if (text.isBlank()) return single(PayloadDiagnosticCodes.KMODEL_NO_OBJECT, whole, "The payload declares no object, so the runtime finds none.")
        val roots = when (val split = splitRoots(text)) {
            is RootSplit.Invalid -> return invalid(whole, "The KModel payload has syntax errors.", split.diagnostics)
            is RootSplit.Roots -> split.roots
        }
        val first = roots.first()
        val found = ArrayList<Diagnostic>()
        if (roots.size > 1) {
            found += PayloadDiagnosticCodes.finding(
                PayloadDiagnosticCodes.KMODEL_SEVERAL_OBJECTS,
                TextRange(roots[1].range.start, roots.last().range.end),
                "The payload declares ${roots.size} root objects; the runtime takes the first and ignores the others.",
            )
        }
        val snapshot = TextSnapshot(text.substring(0, first.range.end), 0)
        val read = lock.withLock {
            when (val metamodel = metamodel()) {
                is MetamodelRead.Failed -> return invalid(whole, "The event definitions could not be read, so the payload cannot be checked.", metamodel.diagnostics)
                is MetamodelRead.Read -> reader.kmodel(snapshot, metamodel.metamodel)
            }
        }
        val model = read.model
        if (read.hasErrors || model == null) return invalid(whole, "The KModel payload does not fit the event definitions.", read.diagnostics)
        val typeName = model.objects.firstOrNull()?.ofType?.name
            ?: return single(PayloadDiagnosticCodes.KMODEL_NO_OBJECT, whole, "The payload declares no object, so the runtime finds none.")
        if (typeName != event.name) {
            found += PayloadDiagnosticCodes.finding(
                PayloadDiagnosticCodes.KMODEL_TYPE,
                first.nameRange,
                "The payload object is a '$typeName', not a '${event.name}'; the runtime uses the payload's own type.",
            )
        }
        return PayloadValidation(found + read.diagnostics)
    }

    /** [text] as a KModel payload. */
    override fun encode(text: String): EventPayload = EventPayload.Kmodel(text)

    /**
     * The root objects [text] consists of, one after the other, or the syntax findings of [text] if it is anything else:
     * each round parses the rest of the text, takes its first root object and goes on after it while the only syntax
     * errors lie behind that object.
     */
    private fun splitRoots(text: String): RootSplit {
        val whole = parser.parse(TextSnapshot(text, 0), SemanticMode.SYNTAX_ONLY)
        val roots = ArrayList<RootBlock>()
        var offset = 0
        var outcome = whole
        while (true) {
            val root = outcome.index.declarationsOf(SymbolKind.OBJECT).filter { !it.range.isEmpty }.minByOrNull { it.range.start }
            if (root == null || outcome.syntaxDiagnostics.any { it.range.start < root.range.end }) return RootSplit.Invalid(whole.syntaxDiagnostics)
            roots += RootBlock(root.range.shiftedBy(offset), root.nameRange.shiftedBy(offset))
            if (outcome.isSyntaxClean) return RootSplit.Roots(roots)
            offset += root.range.end
            outcome = parser.parse(TextSnapshot(text.substring(offset), 0), SemanticMode.SYNTAX_ONLY)
        }
    }

    /** A validation of the one finding [code] at [range] with [message]. */
    private fun single(code: String, range: TextRange, message: String): PayloadValidation =
        PayloadValidation(listOf(PayloadDiagnosticCodes.finding(code, range, message)))

    /** The event metamodel, read on first use; called under [lock]. */
    private fun metamodel(): MetamodelRead =
        eventMetamodel ?: readMetamodel().also { eventMetamodel = it }

    /** Reads the domain metamodel and the events file on top of it. */
    private fun readMetamodel(): MetamodelRead {
        val domain = reader.kmeta(sources.metamodel)
        val domainModel = domain.model
        if (domain.hasErrors || domainModel == null) return MetamodelRead.Failed(domain.diagnostics)
        val events = sources.events ?: return MetamodelRead.Failed(emptyList())
        val read = reader.kmeta(events, domainModel.types)
        val eventModel = read.model
        return if (read.hasErrors || eventModel == null) MetamodelRead.Failed(read.diagnostics) else MetamodelRead.Read(eventModel)
    }

    /** The validation of an invalid snippet: the summary at [range] followed by the [own] findings of parser or reader. */
    private fun invalid(range: TextRange, message: String, own: List<Diagnostic>): PayloadValidation =
        PayloadValidation(listOf(PayloadDiagnosticCodes.finding(PayloadDiagnosticCodes.KMODEL_INVALID, range, message)) + own)

    /**
     * A root object of the snippet.
     *
     * @property range the object's block.
     * @property nameRange the range of its key.
     */
    private data class RootBlock(val range: TextRange, val nameRange: TextRange)

    /** How a snippet splits into root objects. */
    private sealed interface RootSplit {
        /**
         * The snippet is nothing but root objects, one after the other.
         *
         * @property roots the root objects in order, at least one.
         */
        data class Roots(val roots: List<RootBlock>) : RootSplit

        /**
         * The snippet has syntax errors other than further root objects.
         *
         * @property diagnostics the syntax findings of the whole snippet.
         */
        data class Invalid(val diagnostics: List<Diagnostic>) : RootSplit
    }

    /** The outcome of reading the event metamodel. */
    private sealed interface MetamodelRead {
        /**
         * The event metamodel was read.
         *
         * @property metamodel the events file's types followed by the domain types.
         */
        data class Read(val metamodel: Metamodel) : MetamodelRead

        /**
         * A metamodel could not be read.
         *
         * @property diagnostics the reader's findings.
         */
        data class Failed(val diagnostics: List<Diagnostic>) : MetamodelRead
    }

    /** Writes the KModel template of a payload type, four spaces per level; confined to one call of [template]. */
    private inner class TemplateWriter {
        /**
         * The object [id] of [typeName], whose shape is [type] if the schema knows it, at [depth], indented by [indent];
         * a nested object's id is its owner's id and its relation's key, joined by a dash.
         */
        fun obj(id: String, typeName: String, type: TypeShape?, depth: Int, indent: String): String {
            val inner = "$indent    "
            val lines = ArrayList<String>()
            type?.properties?.filter { !it.isList }?.forEach { property ->
                lines += "${inner}prop(${quote(property.key)}) -> ${quote(defaultOf(property.type))}"
            }
            type?.relations?.filter { it.association == AssociationType.EMBEDDED && !it.isList }?.forEach { relation ->
                val target = schema?.type(relation.targetType)
                if (target != null && depth < PayloadForm.DEFAULT_DEPTH_LIMIT) {
                    lines += "${inner}has(${quote(relation.key)}) -> ${obj("$id-${relation.key}", relation.targetType, target, depth + 1, inner)}"
                }
            }
            val head = "make object ${quote(id)}:${quote(typeName)} {"
            return if (lines.isEmpty()) "$head }" else (listOf(head) + lines + "$indent}").joinToString("\n")
        }

        /** The default of a property of [type] as KModel writes it. */
        private fun defaultOf(type: SimplePropertyType): String =
            when (type) {
                SimplePropertyType.NUMBER -> "0.0"
                SimplePropertyType.BOOLEAN -> "false"
                SimplePropertyType.STRING -> ""
            }

        /** [text] in double quotes. */
        private fun quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private companion object {
        /** The id the template gives the payload object. */
        const val PAYLOAD_ID = "payload"
    }
}
