package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.document.parse.SemanticMode
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.diagnostics.DiagnosticsByFile
import io.karpfen.ide.core.workspace.files.FileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * The [AnalyzedFile]s of one analysis run (the conventions on [ProjectAnalysis]): each file is loaded through the
 * [sources] once per run, even when several contexts name it, tokenized unless [SourceText.tokens] are given and parsed
 * with `SemanticMode.SYNTAX_ONLY` unless [SourceText.parse] is given. Files that are no karpfen source or cannot be
 * read are absent. An unexpected failure of a collaborator becomes an ERROR `internal-error` in [problems] instead of
 * ending the analysis. Confined to the run that created it.
 */
internal class AnalyzedFiles(
    private val languages: LanguageRegistry,
    private val sources: AnalysisSources,
    private val problems: DiagnosticsByFile,
) {

    private val loaded = HashMap<Path, AnalyzedFile?>()

    /** The analyzed file at [path] (absolute, normalized), or null. */
    suspend fun get(path: Path): AnalyzedFile? =
        if (path in loaded) loaded[path] else analyze(path).also { loaded[path] = it }

    private suspend fun analyze(path: Path): AnalyzedFile? {
        currentCoroutineContext().ensureActive()
        val name = path.fileName?.toString() ?: return null
        val support = languages.supportFor(name) ?: return null
        return try {
            val source = sources.load(path) ?: return null
            val tokens = source.tokens ?: support.tokenizer.tokenize(source.snapshot)
            val parse = source.parse ?: support.parser.parse(source.snapshot, SemanticMode.SYNTAX_ONLY)
            AnalyzedFile(path, FileKind.of(name), source.snapshot, parse, tokens.diagnostics, source.fileDiagnostics)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            logger.error("Analyzing {} failed", path, e)
            problems.add(path, internalError(e))
            null
        }
    }

    private fun internalError(failure: Exception): Diagnostic =
        Diagnostic(
            range = TextRange.EMPTY,
            severity = Severity.ERROR,
            message = "The file could not be analyzed: ${failure.message ?: failure.javaClass.simpleName}",
            code = DiagnosticCodes.INTERNAL_ERROR,
            source = DiagnosticSource.IDE,
        )

    private companion object {
        private val logger = LoggerFactory.getLogger(AnalyzedFiles::class.java)
    }
}
