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

import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.dsl.DslToolsLoggers
import io.karpfen.ide.core.document.dsl.ReadResult
import io.karpfen.ide.core.document.environment.DescriptorFileRole
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.context.ModelingContext
import io.karpfen.ide.core.workspace.diagnostics.DiagnosticsByFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.MDC
import java.nio.file.Path

/**
 * Reads the files of one [ModelingContext] with dsl-tools. It goes through the [reader] adapter only, because
 * core-workspace never calls dsl-tools directly. The files are read in dependency order. First comes the domain
 * metamodel. Then the events are read with the types of the metamodel as base types. Then each model is read against
 * the metamodel. Each state machine is read on its own, with the optional features of KStates in [languageFeatures].
 *
 * A file is read only if it is free of syntax errors and written in the language of its role, as given by
 * [DescriptorFileRole.language]. So a descriptor that names a `.kstates` file as its metamodel reads nothing, and the
 * resolver reports that on the descriptor. The DSL_TOOLS diagnostics of a read file go to [problems] under its path.
 * Its model goes into the [ContextAnalysis], and it may be partial when the file has errors. The reads themselves go
 * into its [ContextReads].
 *
 * The events and the models depend on the domain metamodel. They are read only when the metamodel was read without
 * errors. Against a partial metamodel they would report its errors a second time, as follow-up errors in other files.
 * For example, a model that sets a property whose type the metamodel misspells would report that property as unknown.
 * So a skipped dependent file reports nothing of its own, and the metamodel's own errors, from syntax or from
 * dsl-tools, explain the gap. The reader checks for cancellation between reads.
 *
 * A loose state machine, one that no context names, is read on its own through [readLoose], with the features in
 * [languageFeatures]. Its DSL_TOOLS diagnostics go to [problems] like those of a context file. A loose model or
 * metamodel is not read. A model needs its metamodel, and a metamodel may use the types of another one, as an events
 * file does. Only a context tells which files belong together, so a read on its own would report types as unknown
 * that are defined elsewhere.
 *
 * A file whose text and input are the same as at its last read in an earlier analysis is not read again. [memory]
 * answers with that earlier read. The input of a state machine is the set [languageFeatures]. The workspace keeps one
 * set object until the features change, so an unchanged set is the same object, and a new set reads every state
 * machine again. While a file is read, the mapped diagnostic context of the logging names it under
 * [DslToolsLoggers.FILE_KEY]. So whatever dsl-tools logs during the read says which file it was.
 */
internal class ContextReader(
    private val reader: DslReader,
    private val memory: ReadMemory,
    private val problems: DiagnosticsByFile,
    private val languageFeatures: Set<KstatesFeature>,
) {

    /** The analysis of [context]. [files] holds its readable files by path. */
    suspend fun read(context: ModelingContext, files: Map<Path, AnalyzedFile>): ContextAnalysis {
        val metamodel = context.metamodel
            ?.let { readable(files, it, DescriptorFileRole.METAMODEL) }
            ?.let { file -> read(file, memory.metamodels, input = null) { reader.kmeta(it) } }
        val domain = metamodel?.takeUnless { it.hasErrors }?.model
        val reads = ContextReads(
            metamodel = metamodel,
            events = domain?.let { readEvents(context, files, it) },
            models = domain?.let { readModels(context, files, it) }.orEmpty(),
            stateMachines = readStateMachines(context, files),
        )
        return ContextAnalysis(
            context = context,
            files = files,
            metamodel = metamodel?.model,
            events = reads.events?.model,
            models = reads.models.modelsOnly(),
            stateMachines = reads.stateMachines.modelsOnly(),
            reads = reads,
        )
    }

    /**
     * Reads the loose [file] on its own if it is a state machine free of syntax errors, as the class describes, and
     * records its diagnostics. Any other file reads nothing. It checks for cancellation first.
     */
    suspend fun readLoose(file: AnalyzedFile) {
        currentCoroutineContext().ensureActive()
        if (file.isReadableAs(DescriptorFileRole.STATE_MACHINE)) {
            read(file, memory.stateMachines, input = languageFeatures) { reader.kstates(it, languageFeatures) }
        }
    }

    /** The read of the events metamodel of [context], with the types of [domain] as base types. Null if there is none. */
    private suspend fun readEvents(context: ModelingContext, files: Map<Path, AnalyzedFile>, domain: Metamodel): ReadResult<Metamodel>? =
        context.events
            ?.let { readable(files, it, DescriptorFileRole.EVENTS) }
            ?.let { file -> read(file, memory.metamodels, input = domain) { reader.kmeta(it, baseTypes = domain.types) } }

    /** The reads of the models of [context] against [domain], by path. */
    private suspend fun readModels(
        context: ModelingContext,
        files: Map<Path, AnalyzedFile>,
        domain: Metamodel,
    ): Map<Path, ReadResult<Model>> {
        val models = LinkedHashMap<Path, ReadResult<Model>>()
        for (path in context.models) {
            val file = readable(files, path, DescriptorFileRole.MODEL) ?: continue
            models[path] = read(file, memory.models, input = domain) { reader.kmodel(it, domain) }
        }
        return models
    }

    /** The reads of the state machines of [context], each read on its own, by path. */
    private suspend fun readStateMachines(context: ModelingContext, files: Map<Path, AnalyzedFile>): Map<Path, ReadResult<StateMachine>> {
        val stateMachines = LinkedHashMap<Path, ReadResult<StateMachine>>()
        for (binding in context.stateMachines) {
            val file = readable(files, binding.file, DescriptorFileRole.STATE_MACHINE) ?: continue
            stateMachines[binding.file] =
                read(file, memory.stateMachines, input = languageFeatures) { reader.kstates(it, languageFeatures) }
        }
        return stateMachines
    }

    /** The models of these reads by path, leaving out the reads that yielded none. */
    private fun <T : Any> Map<Path, ReadResult<T>>.modelsOnly(): Map<Path, T> {
        val models = LinkedHashMap<Path, T>()
        forEach { (path, read) -> read.model?.let { models[path] = it } }
        return models
    }

    /** The file at [path] if it can be read in [role]. It checks for cancellation first. */
    private suspend fun readable(files: Map<Path, AnalyzedFile>, path: Path, role: DescriptorFileRole): AnalyzedFile? {
        currentCoroutineContext().ensureActive()
        return files[path]?.takeIf { it.isReadableAs(role) }
    }

    /** True if this file is free of syntax errors and written in the language of [role]. */
    private fun AnalyzedFile.isReadableAs(role: DescriptorFileRole): Boolean = parse.isSyntaxClean && parse.language == role.language

    /**
     * Reads [file] against [input] with [read], unless [remembered] still holds its read. It records the diagnostics of
     * the result on [file] and returns the result.
     */
    private fun <T : Any> read(
        file: AnalyzedFile,
        remembered: RememberedReads<T>,
        input: Any?,
        read: (TextSnapshot) -> ReadResult<T>,
    ): ReadResult<T> {
        val result = remembered.read(file.path, file.snapshot, input) { snapshot -> namingFile(file) { read(snapshot) } }
        problems.add(file.path, result.diagnostics)
        return result
    }

    /**
     * Runs [read] with [file] named in the mapped diagnostic context of the logging under [DslToolsLoggers.FILE_KEY].
     */
    private fun <T> namingFile(file: AnalyzedFile, read: () -> T): T =
        MDC.putCloseable(DslToolsLoggers.FILE_KEY, file.path.toString()).use { read() }
}
