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

    /**
     * Analyzes [snapshot]; see [DocumentParser] for the contract.
     *
     * @throws NestingLimitExceeded instead of parsing a text of a modeling language that nests deeper than
     *   [NestingLimit.MAX_DEPTH]; never throws otherwise.
     */
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
 * Reads a syntactically clean file with a dsl-tools reader. Implemented in `dsl`; one of the two module-internal seams
 * a [ComposedDocumentParser] is made of. Never throws.
 */
internal fun interface StandaloneSemantics {
    /** Reads [snapshot] into its model and the DSL_TOOLS diagnostics at the places the reader reports. */
    fun read(snapshot: TextSnapshot): StandaloneResult
}

/**
 * Result of [StandaloneSemantics.read].
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
 * not null, it runs [semantics]; then it assembles the [ParseOutcome]. Parsing never throws: a text beyond the
 * [NestingLimit] and a failure of a collaborator each become one diagnostic, from [syntax] a PARSER diagnostic with an
 * empty analysis (the outcome is not syntax-clean, the session shows it and nothing is read), from [semantics] a
 * DSL_TOOLS one without a model.
 *
 * - [NestingLimitExceeded] answers a text that nests deeper than [NestingLimit.MAX_DEPTH]: it becomes a
 *   `nesting-too-deep` error at the brace that opens the first level too many, the same on every parse.
 * - An exception is a defect: it becomes an `internal-error` at the start of the text and is logged as an error with
 *   its stack trace.
 * - A `StackOverflowError` comes from a text within the limit that still nests deeper than the recursive parsers and
 *   tree walkers can follow on the stack of the parsing thread, which only a thread with a stack far smaller than the
 *   default one meets: it becomes a `nesting-too-deep` error at the start of the text and is logged as one line at INFO,
 *   without its stack trace, which would otherwise be repeated on every reparse of the text. At INFO it reaches the IDE
 *   log but neither the IDE's console nor the command-line tool's standard error, which show warnings and errors
 *   only.
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
            semantics?.let { read(it, snapshot) }
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

    /** The syntax analysis; a failure of the analyzer becomes a PARSER diagnostic, so the outcome is not clean. */
    private fun analyzeSyntax(snapshot: TextSnapshot): SyntaxAnalysis =
        try {
            syntax.analyze(snapshot)
        } catch (e: NestingLimitExceeded) {
            failedSyntax(nestingTooDeep(DiagnosticSource.PARSER, e.range, "${NestingLimit.MAX_DEPTH} levels"))
        } catch (e: Exception) {
            logger.error("Syntax analysis of a {} document (version {}) failed", language, snapshot.version, e)
            failedSyntax(internalError(e, "Parsing", DiagnosticSource.PARSER))
        } catch (e: StackOverflowError) {
            logTooDeep("Syntax analysis", snapshot)
            failedSyntax(nestingTooDeep(DiagnosticSource.PARSER))
        }

    /** An empty analysis with the one PARSER [diagnostic] that says why the syntax analysis failed. */
    private fun failedSyntax(diagnostic: Diagnostic): SyntaxAnalysis =
        SyntaxAnalysis(
            syntaxDiagnostics = listOf(diagnostic),
            outline = emptyList(),
            index = SyntaxIndex.EMPTY,
            folding = emptyList(),
            semanticSpans = emptyList(),
        )

    /** The standalone read; a failure of the adapter becomes a DSL_TOOLS diagnostic and no model. */
    private fun read(semantics: StandaloneSemantics, snapshot: TextSnapshot): StandaloneResult =
        try {
            semantics.read(snapshot)
        } catch (e: Exception) {
            logger.error("Standalone read of a {} document (version {}) failed", language, snapshot.version, e)
            StandaloneResult(model = null, diagnostics = listOf(internalError(e, "Reading", DiagnosticSource.DSL_TOOLS)))
        } catch (e: StackOverflowError) {
            logTooDeep("Standalone read", snapshot)
            StandaloneResult(model = null, diagnostics = listOf(nestingTooDeep(DiagnosticSource.DSL_TOOLS)))
        }

    /** Logs in one line, without a stack trace, that [stage] of [snapshot] stopped at the nesting limit. */
    private fun logTooDeep(stage: String, snapshot: TextSnapshot) {
        logger.info(
            "{} of a {} document (version {}) stopped: it nests deeper than the parser can follow",
            stage, language, snapshot.version,
        )
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

    /**
     * The `nesting-too-deep` diagnostic reported by [source] at [range], for a text that nests deeper than [reach]. By
     * default at the start of the text, because a parser that runs out of stack gives no position.
     */
    private fun nestingTooDeep(
        source: DiagnosticSource,
        range: TextRange = TextRange.EMPTY,
        reach: String = "the parser can follow",
    ): Diagnostic =
        Diagnostic(
            range = range,
            severity = Severity.ERROR,
            message = "The ${language.displayName} document nests deeper than $reach; reduce the nesting",
            code = DiagnosticCodes.NESTING_TOO_DEEP,
            source = source,
        )

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(ComposedDocumentParser::class.java)
    }
}
