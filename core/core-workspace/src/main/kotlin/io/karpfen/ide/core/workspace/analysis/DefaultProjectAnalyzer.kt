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

import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.diagnostics.DiagnosticsByFile
import java.nio.file.Path

/**
 * Reads every context in dependency order (metamodel, events, models, state machines), following the rules on
 * [ProjectAnalyzer]. A coordinator over two collaborators per run: [AnalyzedFiles] (loading, tokenizing and parsing
 * each file once) and [ContextReader] (the dsl-tools reads of one context). The project diagnostics start with the
 * resolution's diagnostics; the reads add their DSL_TOOLS diagnostics.
 */
internal class DefaultProjectAnalyzer(
    private val languages: LanguageRegistry,
    private val reader: DslReader,
) : ProjectAnalyzer {

    override suspend fun analyze(resolution: ContextResolution, sources: AnalysisSources, generation: Long): ProjectAnalysis {
        val diagnostics = DiagnosticsByFile().apply { addAll(resolution.diagnostics) }
        val files = AnalyzedFiles(languages, sources, diagnostics)
        val reads = ContextReader(reader, diagnostics)
        val contexts = resolution.contexts.map { context ->
            val contextFiles = LinkedHashMap<Path, AnalyzedFile>()
            for (path in context.files) files.get(path)?.let { contextFiles[path] = it }
            reads.read(context, contextFiles)
        }
        val looseFiles = LinkedHashMap<Path, AnalyzedFile>()
        for (path in resolution.looseFiles) files.get(path)?.let { looseFiles[path] = it }
        return ProjectAnalysis(generation, contexts, diagnostics.build(), looseFiles)
    }
}
