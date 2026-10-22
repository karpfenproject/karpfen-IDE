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

/**
 * Parses one language and walks the tree: the modeling languages with the dsl-tools ANTLR parsers (implemented in
 * `antlr.tree`), environment descriptors with the IDE's JSON parser (implemented in `environment`). One of the two
 * module-internal seams a [ComposedDocumentParser] is made of.
 */
internal interface SyntaxAnalyzer {
    /** The language this analyzer parses. */
    val language: KarpfenLanguage

    /** Never throws; see [DocumentParser] for the contract. */
    fun analyze(snapshot: TextSnapshot): SyntaxAnalysis
}

/** The syntax part of a [ParseOutcome]; its properties mean what the same properties of [ParseOutcome] mean. */
internal data class SyntaxAnalysis(
    val syntaxDiagnostics: List<Diagnostic>,
    val outline: List<OutlineNode>,
    val index: SyntaxIndex,
    val folding: List<FoldingRange>,
    val semanticSpans: List<SemanticSpan>,
)

/**
 * Reads a syntactically clean file with a dsl-tools reader. Implemented in `dsl`; one of the two module-internal seams a
 * [ComposedDocumentParser] is made of. Never throws.
 */
internal fun interface StandaloneSemantics {
    /** Reads [snapshot] into its model and the DSL_TOOLS diagnostics at the places the reader reports. */
    fun convert(snapshot: TextSnapshot): StandaloneResult
}

/**
 * Result of [StandaloneSemantics.convert].
 *
 * @property model the model read, partial if [diagnostics] hold an error; null if the text yields no model.
 * @property diagnostics the DSL_TOOLS diagnostics of the read.
 */
internal data class StandaloneResult(val model: DslModel?, val diagnostics: List<Diagnostic>)

/**
 * The [DocumentParser] of every language, composed (Strategy) of a syntax analyzer (the ANTLR adapter in `antlr.tree`,
 * or the environment analyzer for descriptors) and optional standalone semantics (the dsl-tools adapter in `dsl`), so
 * neither part knows the other and the composition rule lives in this one class.
 *
 * It runs [syntax]; if the outcome has no syntax diagnostics, the mode is [SemanticMode.STANDALONE] and [semantics] is
 * not null, it runs [semantics]; then it assembles the [ParseOutcome]. Any exception from a collaborator (and a
 * `StackOverflowError`, which pathologically deep nesting causes in the recursive ANTLR parser) becomes an
 * `internal-error` diagnostic at the start of the text and is logged, so parsing never throws: from [syntax] a PARSER
 * diagnostic with an empty analysis (the outcome is not syntax-clean, the session shows it and no conversion runs),
 * from [semantics] a DSL_TOOLS one without a model.
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
            semantics?.let { convert(it, snapshot) }
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
            failedSyntax(snapshot, e)
        } catch (e: StackOverflowError) {
            failedSyntax(snapshot, e)
        }

    /** Logs [e] and returns an empty analysis with one PARSER `internal-error`. */
    private fun failedSyntax(snapshot: TextSnapshot, e: Throwable): SyntaxAnalysis {
        logger.error("Syntax analysis of a {} document (version {}) failed", language, snapshot.version, e)
        return SyntaxAnalysis(
            syntaxDiagnostics = listOf(internalError(e, "Parsing", DiagnosticSource.PARSER)),
            outline = emptyList(),
            index = SyntaxIndex.EMPTY,
            folding = emptyList(),
            semanticSpans = emptyList(),
        )
    }

    /** The standalone conversion; a failure of the adapter becomes a DSL_TOOLS `internal-error`. */
    private fun convert(semantics: StandaloneSemantics, snapshot: TextSnapshot): StandaloneResult =
        try {
            semantics.convert(snapshot)
        } catch (e: Exception) {
            failedConversion(snapshot, e)
        } catch (e: StackOverflowError) {
            failedConversion(snapshot, e)
        }

    /** Logs [e] and returns a result without a model and with one DSL_TOOLS `internal-error`. */
    private fun failedConversion(snapshot: TextSnapshot, e: Throwable): StandaloneResult {
        logger.error("Standalone conversion of a {} document (version {}) failed", language, snapshot.version, e)
        return StandaloneResult(model = null, diagnostics = listOf(internalError(e, "Converting", DiagnosticSource.DSL_TOOLS)))
    }

    /** The `internal-error` diagnostic at the start of the text for a failure of [stage] reported by [source]. */
    private fun internalError(e: Throwable, stage: String, source: DiagnosticSource): Diagnostic =
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
