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

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.document.parse.SemanticMode
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.diagnostics.DiagnosticsByFile
import io.karpfen.ide.core.workspace.files.FileKind
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Reads every context in dependency order, following the rules on [ProjectAnalyzer]. The order is metamodel, events,
 * models and then state machines. The loose state machines follow, each read on its own. It coordinates two
 * collaborators per run. [AnalyzedFiles] loads, tokenizes and parses each file once. [ContextReader] does the
 * dsl-tools reads of one context and of the loose state machines. The project diagnostics start with the diagnostics
 * of the resolution, and the reads add their DSL_TOOLS diagnostics.
 *
 * The analyzer of a workspace remembers the reads of its runs in a [ReadMemory]. So a run reads only the files whose
 * text, metamodel or set of language features changed since. Each run forgets the reads of the files that are neither
 * named by a context nor loose any more.
 */
internal class DefaultProjectAnalyzer(
    private val languages: LanguageRegistry,
    private val reader: DslReader,
) : ProjectAnalyzer {

    /** The reads of the earlier runs. */
    private val memory = ReadMemory()

    override suspend fun analyze(
        resolution: ContextResolution,
        sources: AnalysisSources,
        generation: Long,
        languageFeatures: Set<KstatesFeature>,
    ): ProjectAnalysis {
        val diagnostics = DiagnosticsByFile().apply { addAll(resolution.diagnostics) }
        val files = AnalyzedFiles(languages, sources, diagnostics)
        val reads = ContextReader(reader, memory, diagnostics, languageFeatures)
        val contexts = resolution.contexts.map { context ->
            val contextFiles = LinkedHashMap<Path, AnalyzedFile>()
            for (path in context.files) files.get(path)?.let { contextFiles[path] = it }
            reads.read(context, contextFiles)
        }
        val looseFiles = LinkedHashMap<Path, AnalyzedFile>()
        for (path in resolution.looseFiles) files.get(path)?.let { looseFiles[path] = it }
        looseFiles.values.forEach { reads.readLoose(it) }
        memory.retainOnly(resolution.contexts.flatMapTo(HashSet()) { it.files } + resolution.looseFiles)
        return ProjectAnalysis(generation, contexts, diagnostics.build(), looseFiles, languageFeatures)
    }

    /**
     * Parses [text] with the support of the language of [path] and lets a [ContextReader] of its own read it in the
     * context of [path] in [analysis], or on its own if [path] is a loose file. The reader has a fresh memory, so the
     * reads of the analysis runs stay untouched. An unexpected failure is logged and gives an empty list.
     */
    override suspend fun readInPlace(analysis: ProjectAnalysis, path: Path, text: TextSnapshot): List<Diagnostic> {
        val name = path.fileName?.toString() ?: return emptyList()
        val support = languages.supportFor(name) ?: return emptyList()
        return try {
            val file = AnalyzedFile(path, FileKind.of(name), text, support.parser.parse(text, SemanticMode.SYNTAX_ONLY))
            val problems = DiagnosticsByFile()
            val reads = ContextReader(reader, ReadMemory(), problems, analysis.languageFeatures)
            val context = analysis.contextOf(path)
            when {
                context != null -> reads.readAlone(context, file)
                path in analysis.looseFiles -> reads.readLoose(file)
            }
            problems.build()[path].orEmpty()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            logger.error("Reading a text in the place of {} failed", path, e)
            emptyList()
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(DefaultProjectAnalyzer::class.java)
    }
}
