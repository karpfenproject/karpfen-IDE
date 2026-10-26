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

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.document.parse.SemanticMode
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.diagnostics.DiagnosticsByFile
import io.karpfen.ide.core.workspace.diagnostics.UnreadableFindings
import io.karpfen.ide.core.workspace.document.ioFailureReason
import io.karpfen.ide.core.workspace.files.FileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path

/**
 * The [AnalyzedFile]s of one analysis run (the conventions on [ProjectAnalysis]): each file is loaded through the
 * [sources] once per run, even when several contexts name it, tokenized unless [SourceText.tokens] are given and parsed
 * with `SemanticMode.SYNTAX_ONLY` unless [SourceText.parse] is given. Files that are no karpfen source or that vanished
 * are absent; a file that cannot be read is absent and an ERROR `unreadable` in [problems], and an unexpected failure
 * of a collaborator an ERROR `internal-error` there, instead of ending the analysis. Confined to the run that created
 * it.
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

    /** Loads, tokenizes and parses [path]; failures become findings in [problems] and yield null. */
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
        } catch (e: IOException) {
            logger.debug("Not analyzing unreadable {}: {}", path, e.toString())
            problems.add(path, UnreadableFindings.file(ioFailureReason(e)))
            null
        } catch (e: Exception) {
            logger.error("Analyzing {} failed", path, e)
            problems.add(path, internalError(e))
            null
        }
    }

    /** The ERROR `internal-error` at the start of a file whose analysis failed with [failure]. */
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
