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
package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.SyntaxIndex
import java.nio.file.Path

/**
 * Answers what the analyzed files of a workspace declare and use, as far as environment descriptors refer to it. That
 * is the types of a metamodel, the objects of a model, the type a state machine names after `ATTACHED TO`, and the
 * event domains that state machines use.
 *
 * The answers come from the IDE's own syntax index of each file of the [analysis], found in [AnalyzedFile.parse]. They
 * never come from the models that dsl-tools read. So they are known even while a metamodel or a model has errors or
 * was not read at all. A file the analysis does not know declares nothing.
 *
 * These queries live only here. The language features of descriptors and the template of new descriptors both ask
 * this class. Both propose objects for a state machine by the single rule of [AttachmentCandidate.rank]. The class is
 * immutable and thread-safe, like the analysis it reads.
 */
public class ContextSymbols(private val analysis: ProjectAnalysis) {

    /** The names of the types the metamodel at [metamodel] declares, in declaration order. */
    public fun typeNamesOf(metamodel: Path): List<String> =
        indexOf(metamodel)?.declarationsOf(SymbolKind.TYPE)?.map { it.name }.orEmpty()

    /** The objects with a key that the model at [model] declares, nested ones included, in document order. */
    public fun objectsOf(model: Path): List<ModelObject> =
        indexOf(model)
            ?.declarationsOf(SymbolKind.OBJECT)
            ?.filter { it.name.isNotBlank() }
            ?.map { ModelObject(it.name, it.typeName) }
            .orEmpty()

    /** The type the state machine at [machine] names after `ATTACHED TO`, or null if it is unknown. */
    public fun attachedTypeOf(machine: Path): String? =
        indexOf(machine)?.declarationsOf(SymbolKind.STATEMACHINE)?.firstOrNull()?.typeName

    /**
     * The event domains the state machines at [machines] use in `EVENT("domain", ...)`. They are sorted, each appears
     * once, and blank ones are left out.
     */
    public fun eventDomainsOf(machines: List<Path>): List<String> =
        machines
            .flatMap { machine -> indexOf(machine)?.referencesOf(ReferenceKind.EVENT_DOMAIN)?.map { it.name }.orEmpty() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

    /**
     * The objects of the model at [model] that may be proposed for the state machine at [machine] to run attached to.
     * They are ordered best first by [AttachmentCandidate.rank]. Without a known [machine], every object ranks the
     * same.
     */
    public fun attachmentCandidates(model: Path, machine: Path?): List<AttachmentCandidate> =
        AttachmentCandidate.rank(objectsOf(model), machine?.let(::attachedTypeOf))

    /** The syntax index of the analyzed file at [path], or null if the analysis does not know the file. */
    private fun indexOf(path: Path): SyntaxIndex? = analysis.file(path)?.parse?.index
}

/**
 * An object a model declares, as environment descriptors name it.
 *
 * @property key the object's key, the name in `make object "key":"Type"`.
 * @property typeName the object's type, or null if the text omits it.
 */
public data class ModelObject(public val key: String, public val typeName: String?)

/**
 * An object proposed for a state machine to run attached to. In a descriptor this is the `attachedTo` value of a state
 * machine entry.
 *
 * @property modelObject the proposed object. Its key is the value that `attachedTo` takes.
 * @property suitsMachine true if the object's type is the type the state machine names after `ATTACHED TO`.
 */
public data class AttachmentCandidate(public val modelObject: ModelObject, public val suitsMachine: Boolean) {

    /** The ranking rule of candidates. */
    public companion object {
        /**
         * [objects] as candidates for a state machine attached to [attachedType], best first. [attachedType] is null
         * if it is unknown. The objects of that type come first, then the others, and each group keeps the order of
         * [objects]. Objects without a key are left out. A key that occurs more than once is proposed once, at its best
         * place.
         *
         * The descriptor completion ranks objects by this rule. The template of new descriptors uses it too and picks
         * the first candidate as the object a state machine runs attached to.
         */
        public fun rank(objects: List<ModelObject>, attachedType: String?): List<AttachmentCandidate> =
            objects
                .filter { it.key.isNotBlank() }
                .map { AttachmentCandidate(it, suitsMachine = attachedType != null && it.typeName == attachedType) }
                .sortedBy { if (it.suitsMachine) 0 else 1 }
                .distinctBy { it.modelObject.key }
    }
}
