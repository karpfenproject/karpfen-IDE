package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.document.dsl.DslConverter
import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.diagnostics.DiagnosticsByFile
import java.nio.file.Path

/**
 * Converts every context in dependency order (metamodel, events, models, state machines), following the rules on
 * [ProjectAnalyzer]. A coordinator over two collaborators per run: [AnalyzedFiles] (loading, tokenizing and parsing
 * each file once) and [ContextConverter] (the dsl-tools conversions of one context). The project diagnostics start with
 * the resolution's diagnostics; conversions add their DSL_TOOLS diagnostics.
 */
internal class DefaultProjectAnalyzer(
    private val languages: LanguageRegistry,
    private val converter: DslConverter,
) : ProjectAnalyzer {

    override suspend fun analyze(resolution: ContextResolution, sources: AnalysisSources, generation: Long): ProjectAnalysis {
        val diagnostics = DiagnosticsByFile().apply { addAll(resolution.diagnostics) }
        val files = AnalyzedFiles(languages, sources, diagnostics)
        val conversions = ContextConverter(converter, diagnostics)
        val contexts = resolution.contexts.map { context ->
            val contextFiles = LinkedHashMap<Path, AnalyzedFile>()
            for (path in context.files) files.get(path)?.let { contextFiles[path] = it }
            conversions.convert(context, contextFiles)
        }
        val looseFiles = LinkedHashMap<Path, AnalyzedFile>()
        for (path in resolution.looseFiles) files.get(path)?.let { looseFiles[path] = it }
        return ProjectAnalysis(generation, contexts, diagnostics.build(), looseFiles)
    }
}
