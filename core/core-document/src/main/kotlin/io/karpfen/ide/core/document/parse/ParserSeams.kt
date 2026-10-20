package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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
 * exception from a collaborator becomes an `internal-error` diagnostic (never throws) at the start of the text and is
 * logged: from [syntax] a PARSER diagnostic with an empty analysis (so the outcome is not syntax-clean, the session
 * shows it and no conversion runs), from [semantics] a DSL_TOOLS one without a model.
 *
 * @throws IllegalArgumentException if [syntax] parses another language than [language].
 */
internal class ComposedDocumentParser(
    private val language: KarpfenLanguage,
    private val syntax: SyntaxAnalyzer,
    private val semantics: StandaloneSemantics?,
) : DocumentParser {

    init {
        require(syntax.language == language) { "the syntax analyzer parses ${syntax.language}, not $language" }
    }

    override fun parse(snapshot: TextSnapshot, mode: SemanticMode): ParseOutcome {
        val analysis = analyzeSyntax(snapshot)
        val standalone = if (mode == SemanticMode.STANDALONE && analysis.syntaxDiagnostics.isEmpty()) {
            semantics?.let { convert(it, snapshot, analysis.index) }
        } else {
            null
        }
        return ParseOutcome(
            language = language,
            version = snapshot.version,
            syntaxDiagnostics = analysis.syntaxDiagnostics,
            outline = analysis.outline,
            index = analysis.index,
            folding = analysis.folding,
            semanticSpans = analysis.semanticSpans,
            standaloneModel = standalone?.model,
            standaloneDiagnostics = standalone?.diagnostics.orEmpty().sortedWith(Diagnostic.ORDER),
        )
    }

    /** The syntax analysis; a failure of the analyzer becomes a PARSER `internal-error`, so the outcome is not clean. */
    private fun analyzeSyntax(snapshot: TextSnapshot): SyntaxAnalysis =
        try {
            syntax.analyze(snapshot)
        } catch (e: Exception) {
            logger.error("Syntax analysis of a {} document (version {}) failed", language, snapshot.version, e)
            SyntaxAnalysis(
                syntaxDiagnostics = listOf(internalError(e, "Parsing", DiagnosticSource.PARSER)),
                outline = emptyList(),
                index = SyntaxIndex.EMPTY,
                folding = emptyList(),
                semanticSpans = emptyList(),
            )
        }

    /** The standalone conversion; a failure of the adapter becomes a DSL_TOOLS `internal-error`. */
    private fun convert(semantics: StandaloneSemantics, snapshot: TextSnapshot, index: SyntaxIndex): StandaloneResult =
        try {
            semantics.convert(snapshot, index)
        } catch (e: Exception) {
            logger.error("Standalone conversion of a {} document (version {}) failed", language, snapshot.version, e)
            StandaloneResult(model = null, diagnostics = listOf(internalError(e, "Converting", DiagnosticSource.DSL_TOOLS)))
        }

    private fun internalError(e: Exception, stage: String, source: DiagnosticSource): Diagnostic =
        Diagnostic(
            range = TextRange.EMPTY,
            severity = Severity.ERROR,
            message = "$stage the ${language.displayName} document failed internally: ${e.message ?: e.javaClass.name}",
            code = DiagnosticCodes.INTERNAL_ERROR,
            source = source,
        )

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(ComposedDocumentParser::class.java)
    }
}
