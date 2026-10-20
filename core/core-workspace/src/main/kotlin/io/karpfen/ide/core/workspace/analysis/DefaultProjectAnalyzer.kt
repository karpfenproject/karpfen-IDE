package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.document.dsl.DslConverter
import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.workspace.context.ContextResolution

/** Converts every context in dependency order (metamodel, events, models, state machines). */
internal class DefaultProjectAnalyzer(
    private val languages: LanguageRegistry,
    private val converter: DslConverter,
) : ProjectAnalyzer {
    override suspend fun analyze(resolution: ContextResolution, sources: AnalysisSources, generation: Long): ProjectAnalysis =
        TODO("WP-WORKSPACE: analyze ${resolution.contexts.size} contexts")
}
