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
package io.karpfen.ide.runtime.session.examples

import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.fixtures.ExampleOrigin
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.workspace.analysis.ContextAnalysis
import io.karpfen.ide.core.workspace.analysis.ContextSymbols
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.fixtures.TestWorkspaces
import io.karpfen.ide.runtime.protocol.conformance.DescriptorTiming
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.session.mirror.MirrorSeeder
import io.karpfen.ide.runtime.session.mirror.MirrorSnapshot
import io.karpfen.ide.runtime.session.schema.TypeSchema
import io.karpfen.ide.runtime.session.states.MachineLayout
import kotlinx.coroutines.test.TestScope
import java.nio.file.Path

/**
 * One analyzed context of a test workspace, with the inputs the session's packages take, derived as the preparation of
 * a deployment derives them: the schema of the domain metamodel, the seed, one layout per attached machine and the
 * domains the machines use.
 *
 * @property analysis the analysis of the whole workspace.
 * @property context the analysis of the context.
 */
class AnalyzedContext(val analysis: ProjectAnalysis, val context: ContextAnalysis) {
    /** The name of the context's directory. */
    val name: String
        get() = context.context.directory.fileName.toString()

    /** The domain metamodel. */
    val metamodel: Metamodel
        get() = checkNotNull(context.metamodel) { "the context $name has no metamodel" }

    /** The schema of the domain metamodel. */
    val schema: TypeSchema
        get() = TypeSchema.of(metamodel)

    /** The context's one model. */
    val model: Model
        get() = context.models.values.single()

    /** The seed of the model. */
    val seed: MirrorSnapshot
        get() = MirrorSeeder.seed(model, schema)

    /** The attached machines: the attachment of the descriptor, or the first object of the machine's type. */
    val machines: List<AnalyzedMachine>
        get() {
            val symbols = ContextSymbols(analysis)
            val modelPath = context.context.models.single()
            return context.context.stateMachines.map { binding ->
                val attachedTo = binding.attachedTo
                    ?: symbols.attachmentCandidates(modelPath, binding.file).first { it.suitsMachine }.modelObject.key
                AnalyzedMachine(attachedTo, binding.file, machine(binding.file))
            }
        }

    /** The layouts of the attached machines. */
    val layouts: List<MachineLayout>
        get() = machines.map { MachineLayout.of(it.attachedTo, it.file, it.machine) }

    /** The domains the machines use, as the plan lists them. */
    val domains: List<String>
        get() = ContextSymbols(analysis).eventDomainsOf(context.context.stateMachines.map { it.file })

    /** The machine in [file]. */
    private fun machine(file: Path): StateMachine = checkNotNull(context.stateMachines[file]) { "the machine $file was not read" }

    /** The name of the context's directory. */
    override fun toString(): String = name
}

/**
 * An analyzed state machine and the key of the object it runs attached to.
 *
 * @property attachedTo the key of the object.
 * @property file the machine's file.
 * @property machine the analyzed machine.
 */
data class AnalyzedMachine(val attachedTo: String, val file: Path, val machine: StateMachine)

/** Opens test workspaces over the runtime's examples and the synthesized models and analyzes them on virtual time. */
object AnalyzedContexts {
    /** The directories of the runtime's four examples. */
    val RUNTIME_EXAMPLES: List<String> = listOf(
        "single_robot_example", "delivery_drone_example", "autonomous_car_example", "drone_swarm_example",
    )

    /** The analyzed contexts of the runtime's four examples, copied below [root], in [RUNTIME_EXAMPLES] order. */
    suspend fun runtimeExamples(scope: TestScope, root: Path): List<AnalyzedContext> {
        Examples.copyTo(root)
        val analysis = TestWorkspaces(scope).open(root).awaitAnalysis()
        return RUNTIME_EXAMPLES.map { directory ->
            AnalyzedContext(
                analysis,
                analysis.contexts.single { context ->
                    context.context.directory.fileName.toString() == directory &&
                        context.context.directory.parent.fileName.toString() == ExampleOrigin.KARPFEN_RUNTIME.directoryName
                },
            )
        }
    }

    /** The analyzed context of the synthesized [model], copied below [root]. */
    suspend fun synthesized(scope: TestScope, root: Path, model: SynthesizedModel): AnalyzedContext {
        val descriptor = SynthesizedModels.copyTo(root.resolve(model.folder), model, DescriptorTiming.NONE)
        val analysis = TestWorkspaces(scope).open(root).awaitAnalysis()
        return AnalyzedContext(analysis, analysis.contexts.single { it.context.descriptor?.fileName == descriptor.fileName })
    }

    /** The analyzed contexts of every synthesized model, each copied into its own folder below [root], in enum order. */
    suspend fun allSynthesized(scope: TestScope, root: Path): List<AnalyzedContext> {
        val descriptors = SynthesizedModel.entries.map { SynthesizedModels.copyTo(root.resolve(it.folder), it, DescriptorTiming.NONE) }
        val analysis = TestWorkspaces(scope).open(root).awaitAnalysis()
        return descriptors.map { descriptor -> AnalyzedContext(analysis, analysis.contexts.single { it.context.descriptor?.fileName == descriptor.fileName }) }
    }
}
