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
package io.karpfen.ide.core.lang.navigation

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.symbols.DocumentDeclarations
import io.karpfen.ide.core.lang.symbols.LocatedOutcome

/** A symbol that the files of a modeling context reference by name, the subject of cross-file references. */
internal sealed interface ContextSymbol {
    /** The name of the symbol. */
    val name: String

    /** A KMeta type, which is a domain type or an event type. */
    data class Type(override val name: String) : ContextSymbol

    /** The member [name] of the type [owner]. It is a `prop`, `has` or `knows` member. */
    data class Member(val owner: String, override val name: String) : ContextSymbol

    /** An attachable state machine, which `behaves after`, `ATTACH` and `EXCHANGE` name. */
    data class StateMachine(override val name: String) : ContextSymbol
}

/**
 * A file searched for references. It holds the parse outcome with locations and the language of the file. It also holds
 * the text when it is known for the version of the outcome. Model paths need the text to locate segments of paths
 * written with blanks.
 */
internal class SearchFile(val language: KarpfenLanguage, val source: LocatedOutcome, val text: String?)

/**
 * The files of the modeling context of the requesting document, and facts about the context that the contributors
 * share. The document itself comes with its own latest outcome. The other files of the context come with their
 * analyzed outcomes.
 */
internal class ReferenceScope(val request: FeatureRequest, val files: List<SearchFile>) {

    /** The events file of the context among [files], or the document itself if it is one. Null if none of them is. */
    private val eventsFile: SearchFile? = run {
        val events = request.contextAnalysis?.context?.events ?: request.document.path.takeIf { request.isEventsDocument }
        files.firstOrNull { it.source.path == events }
    }

    /** True if [name] is declared as a type in the event metamodel of the context, which makes it an event name. */
    fun isEventType(name: String): Boolean =
        eventsFile?.source?.outcome?.index?.declarations(SymbolKind.TYPE, name)?.isNotEmpty() == true

    companion object {
        /** The scope of [request]. */
        fun of(request: FeatureRequest): ReferenceScope {
            val document = request.document
            val own = DocumentDeclarations(document).latest()?.let { source ->
                SearchFile(document.language, source, document.text.text.takeIf { source.outcome.version == document.text.version })
            }
            val others = request.contextAnalysis?.files?.values.orEmpty()
                .filter { own == null || it.path != document.path }
                .map { SearchFile(it.parse.language, LocatedOutcome.of(it), it.snapshot.text) }
            return ReferenceScope(request, listOfNotNull(own) + others)
        }
    }
}

/**
 * Finds the references of a [ContextSymbol] in one file. Each language has its own strategy, wired by the features
 * factory.
 */
internal fun interface ReferenceContributor {
    /** The locations in [file] that reference [symbol]. */
    fun references(symbol: ContextSymbol, file: SearchFile, scope: ReferenceScope): List<Location>
}

/**
 * Cross-file reference search in a modeling context. It asks every [ReferenceContributor] about every file of the
 * [ReferenceScope] and drops duplicates. The declaration itself is not included, because the providers add it.
 */
internal class ContextReferences(private val contributors: List<ReferenceContributor>) {

    /** The references of [symbol] in the modeling context of [request], or in the document alone if it has none. */
    fun find(symbol: ContextSymbol, request: FeatureRequest): List<Location> {
        val scope = ReferenceScope.of(request)
        return scope.files.flatMap { file -> contributors.flatMap { it.references(symbol, file, scope) } }.distinct()
    }
}

/** Type references of kind `ReferenceKind.TYPE` by name, in files of every language. */
internal class TypeReferences : ReferenceContributor {
    override fun references(symbol: ContextSymbol, file: SearchFile, scope: ReferenceScope): List<Location> =
        if (symbol is ContextSymbol.Type) {
            file.source.outcome.index.references(ReferenceKind.TYPE, symbol.name).map { file.source.location(it.range) }
        } else {
            emptyList()
        }
}

/** The names of attachable state machines, references of kind `ReferenceKind.STATE_MACHINE`, in files of every language. */
internal class StateMachineReferences : ReferenceContributor {
    override fun references(symbol: ContextSymbol, file: SearchFile, scope: ReferenceScope): List<Location> =
        if (symbol is ContextSymbol.StateMachine) {
            file.source.outcome.index.references(ReferenceKind.STATE_MACHINE, symbol.name).map { file.source.location(it.range) }
        } else {
            emptyList()
        }
}
