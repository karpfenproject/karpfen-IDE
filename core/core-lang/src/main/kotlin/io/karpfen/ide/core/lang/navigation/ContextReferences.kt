package io.karpfen.ide.core.lang.navigation

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.symbols.DocumentDeclarations
import io.karpfen.ide.core.lang.symbols.LocatedOutcome

/** A symbol that files of a modeling context reference by name (spec §5, cross-file references). */
internal sealed interface ContextSymbol {
    /** The symbol's name. */
    val name: String

    /** A KMeta type (domain or event type). */
    data class Type(override val name: String) : ContextSymbol

    /** The member [name] (`prop`, `has`, `knows`) of the type [owner]. */
    data class Member(val owner: String, override val name: String) : ContextSymbol
}

/**
 * A file searched for references: its parse outcome with locations, its language and — when known for the outcome's
 * version — its text (model paths need it to locate segments of paths written with blanks).
 */
internal class SearchFile(val language: KarpfenLanguage, val source: LocatedOutcome, val text: String?)

/**
 * The files of the requesting document's modeling context (the document itself with its own latest outcome, the other
 * context files with their analyzed outcomes), and facts about the context shared by the contributors.
 */
internal class ReferenceScope(val request: FeatureRequest, val files: List<SearchFile>) {

    private val eventsFile: SearchFile? = run {
        val events = request.contextAnalysis?.context?.events ?: request.document.path.takeIf { request.isEventsDocument }
        files.firstOrNull { it.source.path == events }
    }

    /** True if [name] is declared as a type in the context's event metamodel (an event name). */
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

/** Finds the references of a [ContextSymbol] in one file (Strategy per language, wired by the features factory). */
internal fun interface ReferenceContributor {
    /** The locations in [file] that reference [symbol]. */
    fun references(symbol: ContextSymbol, file: SearchFile, scope: ReferenceScope): List<Location>
}

/**
 * Cross-file reference search of a modeling context (spec §5): asks every [ReferenceContributor] about every file of
 * the [ReferenceScope]; duplicates are dropped. The declaration itself is not included (the providers add it).
 */
internal class ContextReferences(private val contributors: List<ReferenceContributor>) {

    /** The references of [symbol] in the modeling context of [request] (the document alone if it has none). */
    fun find(symbol: ContextSymbol, request: FeatureRequest): List<Location> {
        val scope = ReferenceScope.of(request)
        return scope.files.flatMap { file -> contributors.flatMap { it.references(symbol, file, scope) } }.distinct()
    }
}

/** Type references (`ReferenceKind.TYPE`) by name, in files of every language. */
internal class TypeReferences : ReferenceContributor {
    override fun references(symbol: ContextSymbol, file: SearchFile, scope: ReferenceScope): List<Location> =
        if (symbol is ContextSymbol.Type) {
            file.source.outcome.index.references(ReferenceKind.TYPE, symbol.name).map { file.source.location(it.range) }
        } else {
            emptyList()
        }
}
