package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.workspace.analysis.ContextSymbols
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.ui.workbench.templates.TemplateContext
import java.nio.file.Path

/**
 * Builds the [TemplateContext] of a new file from the workspace's contexts and the [ContextSymbols] of its latest
 * analysis, the same queries the descriptor's language features ask, so the mediator never walks converted dsl-tools
 * models and the context is also known while a metamodel does not convert: the directory's context (its explicit
 * context if a descriptor covers it, else its implicit one), the types of the context's metamodel, the objects of its
 * first model, each state machine's `ATTACHED TO` type and the event domains the state machines use. Pure.
 */
internal object TemplateContextBuilder {

    /** The template context of a new file in [directory]; without a context there, only the directory is known. */
    fun build(directory: Path, resolution: ContextResolution, analysis: ProjectAnalysis): TemplateContext {
        val context = resolution.contexts.firstOrNull { it.directory == directory } ?: return TemplateContext(directory)
        val symbols = ContextSymbols(analysis)
        val machines = context.stateMachines.map { it.file }
        return TemplateContext(
            directory = directory,
            context = context,
            typeNames = context.metamodel?.let(symbols::typeNamesOf).orEmpty(),
            objects = context.models.firstOrNull()?.let(symbols::objectsOf).orEmpty(),
            stateMachineTypes = machines.mapNotNull { file -> symbols.attachedTypeOf(file)?.let { file to it } }.toMap(),
            eventDomains = symbols.eventDomainsOf(machines),
        )
    }
}
