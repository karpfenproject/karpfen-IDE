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
package io.karpfen.ide.runtime.session.plan

import io.karpfen.dsl.instance.Model
import io.karpfen.ide.core.document.environment.EnvironmentDescriptor
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.analysis.ContextAnalysis
import io.karpfen.ide.core.workspace.analysis.ContextSymbols
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.context.ModelingContext
import io.karpfen.ide.core.workspace.context.StateMachineBinding
import io.karpfen.ide.runtime.session.problem.SourceLocation
import java.nio.file.Path

/**
 * What the checks of one planning run look at. That is the analysis, the descriptor's explicit context in it and the
 * facts derived from them. Each fact is computed once on first use. Confined to the planning call. It reads dsl-tools
 * models and is never shared.
 *
 * @property analysis the analysis the plan is read from.
 * @property descriptor the absolute, normalized path of the environment descriptor.
 * @property unsaved the absolute, normalized paths that differ from their saved state.
 */
internal class PlanSubject(
    val analysis: ProjectAnalysis,
    val descriptor: Path,
    val unsaved: Set<Path>,
) {
    /** The analysis of the explicit context of [descriptor], or null if the descriptor resolves to none. */
    val context: ContextAnalysis? = analysis.contexts.firstOrNull { it.context.descriptor == descriptor }

    /** The modeling context of [context]. */
    val modelingContext: ModelingContext?
        get() = context?.context

    /** The typed descriptor the context was resolved from. */
    val environment: EnvironmentDescriptor?
        get() = modelingContext?.environment

    /** The domain metamodel file of the context. */
    val metamodelFile: Path?
        get() = modelingContext?.metamodel

    /** The model file of the context. An explicit context names at most one. */
    val modelFile: Path?
        get() = modelingContext?.models?.firstOrNull()

    /** The model as dsl-tools read it, or null if it was not read. */
    val model: Model?
        get() = modelFile?.let { context?.models?.get(it) }

    /** The state machines of the context with their attachments, in descriptor order. */
    val machines: List<StateMachineBinding>
        get() = modelingContext?.stateMachines.orEmpty()

    /** The files of the context and the descriptor, which is everything the gate reads. */
    val gatedFiles: List<Path>
        get() = modelingContext?.files.orEmpty() + listOf(descriptor)

    /** The runtime's index of the model, or null if the model was not read. */
    val keyedObjects: KeyedObjects? by lazy { model?.let(KeyedObjects::of) }

    /**
     * Every event domain of the state machines' conditions and actions, sorted and once each. Blank domains are left
     * out.
     */
    val domains: List<String> by lazy { ContextSymbols(analysis).eventDomainsOf(machines.map { it.file }) }

    /** Where the objects, types and states of the context's files are declared. */
    val locations: DeclarationLocations by lazy { DeclarationIndex.locationsOf(analysis, modelingContext?.files.orEmpty()) }

    /** The descriptor as a whole. It has no analyzed text. */
    val descriptorLocation: SourceLocation
        get() = SourceLocation(descriptor, null, null)

    /** [file] as a whole, with its analyzed text if it has one. */
    fun fileLocation(file: Path): SourceLocation = SourceLocation(file, null, analysis.file(file)?.snapshot)

    /** [range] in the analyzed text of [file], or the file as a whole if the range does not fit that text. */
    fun rangeLocation(file: Path, range: TextRange): SourceLocation {
        val snapshot = analysis.file(file)?.snapshot
        val fits = snapshot == null || range.end <= snapshot.length
        return SourceLocation(file, range.takeIf { fits }, snapshot)
    }

    /** The declaration of the state machine in [file], at its name, or the file as a whole. */
    fun machineLocation(file: Path): SourceLocation =
        analysis.file(file)?.parse?.index?.declarationsOf(SymbolKind.STATEMACHINE)?.firstOrNull()
            ?.let { rangeLocation(file, it.nameRange) }
            ?: fileLocation(file)

    /** The declaration of the object with [key] in the model, or the model file as a whole. */
    fun objectLocation(key: String): SourceLocation? =
        modelFile?.let { file -> locations.objectIn(file, key) ?: fileLocation(file) }

    /** The file name of [file], as messages show it. */
    fun nameOf(file: Path): String = file.fileName?.toString() ?: file.toString()
}
