package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.ui.workbench.templates.TemplateContext
import io.karpfen.ide.ui.workbench.templates.TemplateObject
import java.nio.file.Path

/**
 * Builds the [TemplateContext] of a new file (decision 45 of the work-package plan) from the workspace's contexts and
 * the IDE-owned syntax indexes of its latest analysis — never from converted dsl-tools models, so it is also known
 * while a metamodel does not convert: the directory's context (its explicit context if a descriptor covers it, else its
 * implicit one), the TYPE declarations of the context's metamodel, the OBJECT declarations of its first model and each
 * state machine's `ATTACHED TO` type. Pure.
 */
internal object TemplateContextBuilder {

    fun build(directory: Path, resolution: ContextResolution, analysis: ProjectAnalysis): TemplateContext {
        val context = resolution.contexts.firstOrNull { it.directory == directory } ?: return TemplateContext(directory)
        fun index(path: Path?): SyntaxIndex? = path?.let { analysis.file(it)?.parse?.index }
        val typeNames = index(context.metamodel)?.declarationsOf(SymbolKind.TYPE)?.map { it.name }.orEmpty()
        val objects = index(context.models.firstOrNull())
            ?.declarationsOf(SymbolKind.OBJECT)
            ?.map { TemplateObject(it.name, it.typeName) }
            .orEmpty()
        val machineTypes = context.stateMachines.mapNotNull { binding ->
            index(binding.file)?.declarationsOf(SymbolKind.STATEMACHINE)?.firstOrNull()?.typeName?.let { binding.file to it }
        }.toMap()
        return TemplateContext(directory, context, typeNames, objects, machineTypes)
    }
}
