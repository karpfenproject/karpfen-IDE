package io.karpfen.ide.core.workspace.analysis

import instance.Model
import io.karpfen.ide.core.document.dsl.Conversion
import io.karpfen.ide.core.document.dsl.DslConverter
import io.karpfen.ide.core.workspace.context.ModelingContext
import io.karpfen.ide.core.workspace.context.language
import io.karpfen.ide.core.workspace.environment.DescriptorFileRole
import io.karpfen.ide.core.workspace.diagnostics.DiagnosticsByFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import states.StateMachine
import java.nio.file.Path

/**
 * Converts the files of one [ModelingContext] with dsl-tools, through the [converter] adapter only (spec §4,
 * decision 44), in dependency order: the domain metamodel; the events with the metamodel's types as base types; each
 * model against the metamodel; each state machine on its own.
 *
 * A file is converted only if it is syntax-clean and written in the language of its role
 * ([DescriptorFileRole.language]; a descriptor naming a `.kstates` file as metamodel converts nothing, which the
 * resolver reports on the descriptor; dsl-tools would print ANTLR errors for a foreign grammar). A conversion that
 * needs an unconverted metamodel (events, models) is skipped and reports nothing of its own. DSL_TOOLS diagnostics go
 * to [problems] under the converted file's path. Checks for cancellation between conversions.
 */
internal class ContextConverter(private val converter: DslConverter, private val problems: DiagnosticsByFile) {

    /** The analysis of [context] whose readable files are [files] (by path). */
    suspend fun convert(context: ModelingContext, files: Map<Path, AnalyzedFile>): ContextAnalysis {
        val metamodel = context.metamodel
            ?.let { convertible(files, it, DescriptorFileRole.METAMODEL) }
            ?.let { file -> record(file, converter.kmeta(file.snapshot, file.parse.index)) }
        val events = if (metamodel == null) {
            null
        } else {
            context.events
                ?.let { convertible(files, it, DescriptorFileRole.EVENTS) }
                ?.let { file -> record(file, converter.kmeta(file.snapshot, file.parse.index, baseTypes = metamodel.types)) }
        }
        val models = LinkedHashMap<Path, Model>()
        if (metamodel != null) {
            for (path in context.models) {
                val file = convertible(files, path, DescriptorFileRole.MODEL) ?: continue
                record(file, converter.kmodel(file.snapshot, file.parse.index, metamodel))?.let { models[path] = it }
            }
        }
        val stateMachines = LinkedHashMap<Path, StateMachine>()
        for (binding in context.stateMachines) {
            val file = convertible(files, binding.file, DescriptorFileRole.STATE_MACHINE) ?: continue
            record(file, converter.kstates(file.snapshot, file.parse.index))?.let { stateMachines[binding.file] = it }
        }
        return ContextAnalysis(context, files, metamodel, events, models, stateMachines)
    }

    /** The file at [path] if it can be converted in [role]; checks for cancellation first. */
    private suspend fun convertible(files: Map<Path, AnalyzedFile>, path: Path, role: DescriptorFileRole): AnalyzedFile? {
        currentCoroutineContext().ensureActive()
        return files[path]?.takeIf { it.parse.isSyntaxClean && it.parse.language == role.language }
    }

    private fun <T : Any> record(file: AnalyzedFile, conversion: Conversion<T>): T? {
        problems.add(file.path, conversion.diagnostics)
        return conversion.value
    }
}
