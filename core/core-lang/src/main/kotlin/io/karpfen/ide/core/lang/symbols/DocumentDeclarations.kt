package io.karpfen.ide.core.lang.symbols

import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.parse.SymbolDeclaration
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.TextLineage
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.analysis.AnalyzedFile
import java.nio.file.Path

/**
 * A parse outcome together with where its ranges live: the file and the [TextLineage] its version counts in, so every
 * range taken from it becomes a [Location] with the outcome's version, which navigation maps into the live text.
 */
internal class LocatedOutcome(val path: Path, val outcome: ParseOutcome, val lineage: TextLineage) {

    /** [range] of this outcome as a location. */
    fun location(range: TextRange): Location = Location(path, range, outcome.version, lineage)

    companion object {
        /** The outcome of an analyzed file of the modeling context (its snapshot's version and lineage). */
        fun of(file: AnalyzedFile): LocatedOutcome = LocatedOutcome(file.path, file.parse, file.snapshot.lineage)
    }
}

/** A declaration and the outcome it was found in. */
internal class DeclaredSymbol(val declaration: SymbolDeclaration, val source: LocatedOutcome) {
    /** Where the declared name is. */
    val location: Location get() = source.location(declaration.nameRange)
}

/**
 * The declarations of a document by the staleness rule of [LanguageDocument]: name-based knowledge from its latest
 * parse, completed by its last good parse for what the latest (broken) one lost, each with the version of the outcome
 * it came from (so a declaration only known from the last good parse navigates with that parse's version). Never makes
 * offset lookups. One instance per request.
 */
internal class DocumentDeclarations(private val document: LanguageDocument) {

    private val outcomes: List<LocatedOutcome> =
        listOfNotNull(document.parse, document.lastGoodParse)
            .distinctBy { it.version }
            .map { LocatedOutcome(document.path, it, document.text.lineage) }

    /** The document's parse outcomes, latest first. */
    fun located(): List<LocatedOutcome> = outcomes

    /** The outcome for references in the document: the current parse, else the latest, else the last good one. */
    fun latest(): LocatedOutcome? =
        outcomes.firstOrNull { it.outcome.version == document.text.version } ?: outcomes.firstOrNull()

    /** The declarations of [kind] named [name] from the first outcome that has any. */
    fun named(kind: SymbolKind, name: String): List<DeclaredSymbol> {
        for (source in outcomes) {
            val found = source.outcome.index.declarations(kind, name)
            if (found.isNotEmpty()) return found.map { DeclaredSymbol(it, source) }
        }
        return emptyList()
    }

    /**
     * Every declaration of [kind] (non-empty names): those of the latest outcome, then those of older outcomes whose
     * name and container the latest one lacks.
     */
    fun all(kind: SymbolKind): List<DeclaredSymbol> {
        val seen = HashSet<Pair<String, String?>>()
        val result = ArrayList<DeclaredSymbol>()
        for (source in outcomes) {
            val fresh = source.outcome.index.declarationsOf(kind).filter { it.name.isNotEmpty() && (it.name to it.container) !in seen }
            fresh.forEach { result += DeclaredSymbol(it, source) }
            fresh.forEach { seen += it.name to it.container }
        }
        return result
    }
}
