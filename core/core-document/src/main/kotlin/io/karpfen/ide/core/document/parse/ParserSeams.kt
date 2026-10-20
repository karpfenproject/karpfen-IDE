package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.text.TextSnapshot

// Module-internal seams of the parse pipeline (WP-PARSE). A [DocumentParser] is composed (Strategy) of a syntax
// analyzer (ANTLR adapter, package `antlr.tree`) and optional standalone semantics (dsl-tools adapter, package `dsl`),
// so neither adapter knows the other and the composition rule lives in one place ([ComposedDocumentParser]).

/** Parses one language with the dsl-tools ANTLR parser and walks the tree. Implemented in `antlr.tree`. */
internal interface SyntaxAnalyzer {
    /** The language this analyzer parses. */
    val language: KarpfenLanguage

    /** Never throws; see [DocumentParser] for the contract. */
    fun analyze(snapshot: TextSnapshot): SyntaxAnalysis
}

/** The syntax part of a [ParseOutcome]. */
internal data class SyntaxAnalysis(
    val syntaxDiagnostics: List<Diagnostic>,
    val outline: List<OutlineNode>,
    val index: SyntaxIndex,
    val folding: List<FoldingRange>,
    val semanticSpans: List<SemanticSpan>,
)

/** Converts a syntactically clean file with a dsl-tools converter. Implemented in `dsl`. Never throws. */
internal fun interface StandaloneSemantics {
    /** Converts [snapshot]; [index] locates exception messages (see `dsl.DslErrorLocator`). */
    fun convert(snapshot: TextSnapshot, index: SyntaxIndex): StandaloneResult
}

/** Result of [StandaloneSemantics.convert]. */
internal data class StandaloneResult(val model: DslModel?, val diagnostics: List<Diagnostic>)

/**
 * The [DocumentParser] of every language: runs [syntax]; if the outcome has no syntax diagnostics, the mode is
 * [SemanticMode.STANDALONE] and [semantics] is not null, runs [semantics]; assembles the [ParseOutcome]. Any
 * exception from a collaborator becomes an `internal-error` diagnostic (never throws).
 */
internal class ComposedDocumentParser(
    private val language: KarpfenLanguage,
    private val syntax: SyntaxAnalyzer,
    private val semantics: StandaloneSemantics?,
) : DocumentParser {

    override fun parse(snapshot: TextSnapshot, mode: SemanticMode): ParseOutcome =
        TODO("WP-PARSE: compose syntax analysis and standalone semantics for $language")
}
