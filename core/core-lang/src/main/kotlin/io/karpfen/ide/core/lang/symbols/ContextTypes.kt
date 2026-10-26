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
package io.karpfen.ide.core.lang.symbols

import io.karpfen.dsl.meta.AssociationType
import io.karpfen.dsl.meta.ClassType
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.workspace.analysis.AnalyzedFile
import io.karpfen.ide.core.workspace.analysis.ContextAnalysis
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * The types of one modeling context as immutable values: [domain] from its domain metamodel, [events] from its event
 * metamodel (only the TYPE declarations of the events file itself, because dsl-tools adds the domain base types to the
 * events metamodel it reads).
 */
internal class ContextTypes(val domain: List<TypeInfo>, val events: List<TypeInfo>) {
    /** The domain types, then the event types, a name once. */
    val all: List<TypeInfo> = (domain + events).distinctBy { it.name }

    companion object {
        /**
         * Converts [context]: a metamodel that dsl-tools read ([ContextAnalysis.metamodel],
         * [ContextAnalysis.events]), complete or partial, gives the types and members (dsl-tools is authoritative);
         * the declaration locations come from the analyzed file's syntax index. What the read left out and the file's
         * syntax declares is added from the syntax ([SyntaxTypes]), with its type as written: a partial metamodel
         * leaves out a property of an unknown type, for example, which completion, hover and go to definition still
         * offer while the user fixes it. A metamodel that was not read (its syntax is broken, or the events wait for a
         * domain metamodel without errors) falls back to its file's syntax entirely, so completion keeps working while
         * the metamodel is being edited.
         */
        fun convert(context: ContextAnalysis): ContextTypes {
            val domainFile = context.context.metamodel?.let(context.files::get)
            val eventsFile = context.context.events?.let(context.files::get)
            val domain = typesOf(domainFile, context.metamodel, TypeOrigin.DOMAIN, names = null)
            val eventNames = eventsFile?.parse?.index?.declarationsOf(SymbolKind.TYPE)?.map { it.name }?.filter { it.isNotEmpty() }?.toSet()
            val events = if (eventNames == null) emptyList() else typesOf(eventsFile, context.events, TypeOrigin.EVENTS, eventNames)
            return ContextTypes(domain, events)
        }

        /**
         * The types of [metamodel] (restricted to [names] when given) located in [file] and completed by its syntax,
         * or the types of [file]'s syntax alone when the metamodel was not read.
         */
        private fun typesOf(file: AnalyzedFile?, metamodel: Metamodel?, origin: TypeOrigin, names: Set<String>?): List<TypeInfo> {
            val located = file?.let(LocatedOutcome::of)
            val declared = located?.let { SyntaxTypes.read(it, origin, names) }.orEmpty()
            if (metamodel == null) return declared
            val types = metamodel.types.filter { names == null || it.name in names }.distinctBy { it.name }
            return types.map { MetamodelTypes.convert(it, origin, located) }.completedBy(declared)
        }

        /**
         * These types, as dsl-tools read them, completed by the [declared] types of the file's syntax: a member the
         * read left out joins its type in the file's order, and a type the read left out follows the others.
         */
        private fun List<TypeInfo>.completedBy(declared: List<TypeInfo>): List<TypeInfo> {
            val read = associateBy { it.name }
            val declaredByName = declared.associateBy { it.name }
            val completed = map { type -> declaredByName[type.name]?.let { type.withMembersOnlyIn(it) } ?: type }
            return completed + declared.filter { it.name !in read }
        }

        /** This type with the members of [declared] it lacks, all in the order of their declarations in the file. */
        private fun TypeInfo.withMembersOnlyIn(declared: TypeInfo): TypeInfo {
            val names = members.mapTo(HashSet()) { it.name }
            val missing = declared.members.filter { it.name !in names }
            if (missing.isEmpty()) return this
            return copy(members = (members + missing).sortedBy { it.declaration?.range?.start ?: Int.MAX_VALUE })
        }
    }
}

/**
 * Converts dsl-tools [ClassType]s into [TypeInfo]s, locating the declarations in the syntax index of the metamodel's
 * analyzed file. Members keep the file's order (simple and object properties are separate lists in dsl-tools).
 */
internal object MetamodelTypes {

    /** The [TypeInfo] of [type]; declarations stay null without a [file] or where its syntax index lacks them. */
    fun convert(type: ClassType, origin: TypeOrigin, file: LocatedOutcome?): TypeInfo {
        val index = file?.outcome?.index
        fun locate(kind: SymbolKind, name: String, container: String?) =
            index?.declarations(kind, name)?.firstOrNull { it.container == container }?.let { file.location(it.nameRange) }

        val simple = type.simpleProperties.map { property ->
            MemberInfo(
                name = property.key,
                kind = MemberKind.PROPERTY,
                typeName = property.propertyType.keyword,
                isList = property.isList,
                declaration = locate(SymbolKind.SIMPLE_PROPERTY, property.key, type.name),
            )
        }
        val objects = type.objectProperties.map { property ->
            val kind = if (property.associationType == AssociationType.EMBEDDED) MemberKind.HAS else MemberKind.KNOWS
            MemberInfo(
                name = property.key,
                kind = kind,
                typeName = property.reference.classTypeName,
                isList = property.isList,
                declaration = locate(SyntaxTypes.declarationKindOf(kind), property.key, type.name),
            )
        }
        return TypeInfo(
            name = type.name,
            description = type.comment,
            members = (simple + objects).sortedBy { it.declaration?.range?.start ?: Int.MAX_VALUE },
            origin = origin,
            declaration = locate(SymbolKind.TYPE, type.name, container = null),
        )
    }
}

/**
 * Memoizes [ContextTypes.convert] per `ContextAnalysis` identity for the latest [ProjectAnalysis] only (a new
 * analysis generation drops the conversions of the previous one). Thread-safe; a conversion may run twice under a
 * race, which is harmless (it is pure).
 */
internal class ContextTypesMemo {

    /** The conversions made for one [project] analysis. */
    private class Generation(val project: ProjectAnalysis) {
        val byContext = ConcurrentHashMap<IdentityKey, ContextTypes>()
    }

    /** Identity of a `ContextAnalysis` (data-class equality would compare whole analyses). */
    private class IdentityKey(val context: ContextAnalysis) {
        override fun equals(other: Any?): Boolean = other is IdentityKey && other.context === context

        override fun hashCode(): Int = System.identityHashCode(context)
    }

    /** The conversions of the latest analysis asked for. */
    private val current = AtomicReference(Generation(ProjectAnalysis.EMPTY))

    /** The types of [context], an analysis of [project]. */
    fun typesOf(project: ProjectAnalysis, context: ContextAnalysis): ContextTypes {
        val generation: Generation = current.updateAndGet { if (it.project === project) it else Generation(project) }
        return generation.byContext.computeIfAbsent(IdentityKey(context)) { ContextTypes.convert(context) }
    }
}
