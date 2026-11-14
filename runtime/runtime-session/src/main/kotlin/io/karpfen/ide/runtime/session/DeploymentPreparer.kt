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
package io.karpfen.ide.runtime.session

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.analysis.ContextAnalysis
import io.karpfen.ide.core.workspace.analysis.ContextSymbols
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.core.workspace.document.SaveResult
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptorReader
import io.karpfen.ide.runtime.session.events.EventCatalogBuilder
import io.karpfen.ide.runtime.session.events.EventSources
import io.karpfen.ide.runtime.session.events.ListeningMachine
import io.karpfen.ide.runtime.session.mirror.MirrorSeeder
import io.karpfen.ide.runtime.session.plan.DeploymentPlan
import io.karpfen.ide.runtime.session.plan.DeploymentPlanner
import io.karpfen.ide.runtime.session.plan.PlanResult
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import io.karpfen.ide.runtime.session.problem.SourceLocation
import io.karpfen.ide.runtime.session.schema.TypeSchema
import io.karpfen.ide.runtime.session.states.DefinitionLayout
import io.karpfen.ide.runtime.session.states.MachineLayout
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Prepares the deployment of one environment descriptor from a workspace. The goal is that what the problems gate
 * checked is exactly what runs. The preparation takes these steps:
 *
 * 1. It saves every open document, because a deployment uploads the saved files. A failed save blocks the deployment
 *    with `deploy-save-failed`.
 * 2. It awaits the analysis of the saved state.
 * 3. It finds the unsaved paths. An open document counts as unsaved when it is still dirty, or when its analyzed
 *    snapshot is not its current one, which means another lineage or version. Both happen when a document was edited
 *    while the analysis ran. An open environment descriptor also counts as unsaved when its current text does not
 *    decode to the descriptor that its analyzed context was resolved from. The workspace reads an edited descriptor
 *    again on its own schedule, so the analysis may still hold an earlier one. Without this check, the deploy
 *    configuration of that earlier descriptor would be deployed.
 * 4. It plans the deployment with [planner].
 * 5. It turns the analysis into immutable values. These are the schema of the domain metamodel, the seed of the model,
 *    one layout per attached machine and the event catalog of the plan's domains.
 *
 * Nothing the preparation returns holds a dsl-tools object. The dsl-tools objects of the analysis are read only here.
 * Stateless.
 *
 * @param planner plans the deployment from the analysis.
 * @param descriptorReader decodes the current text of an open environment descriptor, to compare it with the
 *   descriptor of its analyzed context.
 */
internal class DeploymentPreparer(
    private val planner: DeploymentPlanner,
    private val descriptorReader: EnvironmentDescriptorReader,
) {

    /** Prepares the deployment of [descriptor] from [workspace]. */
    suspend fun prepare(workspace: Workspace, descriptor: Path): Preparation {
        val failedSaves = workspace.documents.saveAll().filterIsInstance<SaveResult.Failed>()
        if (failedSaves.isNotEmpty()) {
            logger.info("Deployment of {} blocked: {} documents could not be saved", descriptor, failedSaves.size)
            return Preparation.Blocked(failedSaves.map(::saveFailed))
        }
        val analysis = workspace.awaitAnalysis()
        val unsaved = workspace.documents.openDocuments.value.filter { isUnsaved(it, analysis) }.mapTo(HashSet()) { it.path }
        return when (val planned = planner.plan(analysis, descriptor, unsaved)) {
            is PlanResult.Blocked -> Preparation.Blocked(planned.findings)
            is PlanResult.Ready -> Preparation.Ready(prepared(analysis, planned.plan))
        }
    }

    /**
     * True if [document] differs from what the deployment would upload or configure. That is the case when it is
     * dirty, when the analysis read another text of it than its current one, or when it is the descriptor of an
     * analyzed context that was resolved from another descriptor than its current text decodes to.
     */
    private fun isUnsaved(document: OpenDocument, analysis: ProjectAnalysis): Boolean {
        if (document.isDirty.value) return true
        val current = document.session.text.snapshot.value
        analysis.contexts.firstOrNull { it.context.descriptor == document.path }?.let { described ->
            return descriptorReader.read(document.path, current.text).descriptor != described.context.environment
        }
        val analyzed = analysis.file(document.path)?.snapshot ?: return false
        return analyzed.lineage != current.lineage || analyzed.version != current.version
    }

    /** The values a session needs to deploy [plan], read from [analysis]. */
    private fun prepared(analysis: ProjectAnalysis, plan: DeploymentPlan): PreparedDeployment {
        val context = contextOf(analysis, plan)
        val metamodel = checkNotNull(context.metamodel) { "a ready plan has a domain metamodel" }
        val model = checkNotNull(context.models[plan.model.file]) { "a ready plan has an analyzed model" }
        val schema = TypeSchema.of(metamodel)
        val machines = plan.attachments.map { attachment ->
            val machine = checkNotNull(context.stateMachines[attachment.file]) { "a ready plan has every state machine analyzed" }
            ListeningMachine(attachment.attachedTo, attachment.file, machine)
        }
        val definitions = plan.definitions.map { definition ->
            val machine = checkNotNull(context.stateMachines[definition.file]) { "a ready plan has every definition analyzed" }
            definition to ListeningMachine(null, definition.file, machine)
        }
        val catalog = EventCatalogBuilder.build(
            domains = plan.domains,
            machines = machines + definitions.map { it.second },
            eventsMetamodel = context.events,
            eventTypeNames = plan.events?.let { ContextSymbols(analysis).typeNamesOf(it.file) }.orEmpty(),
            sources = EventSources(plan.metamodel.snapshot, plan.events?.snapshot),
        )
        return PreparedDeployment(
            plan = plan,
            schema = schema,
            seed = MirrorSeeder.seed(model, schema),
            layouts = machines.map { MachineLayout.of(checkNotNull(it.attachedTo), it.file, it.machine) },
            catalog = catalog,
            definitions = definitions.map { (definition, listening) -> DefinitionLayout.of(definition.name, definition.file, listening.machine) },
        )
    }

    /** The analyzed context whose descriptor is the descriptor of [plan]. */
    private fun contextOf(analysis: ProjectAnalysis, plan: DeploymentPlan): ContextAnalysis =
        checkNotNull(analysis.contexts.firstOrNull { it.context.descriptor == plan.descriptor }) {
            "a ready plan has the context of its descriptor ${plan.descriptor}"
        }

    /** The problem that [failed] could not be saved. */
    private fun saveFailed(failed: SaveResult.Failed): DeployProblem =
        DeployProblem(
            Severity.ERROR,
            DeployProblemCodes.SAVE_FAILED,
            "${failed.path.fileName} could not be saved before the deployment: ${failed.message}",
            SourceLocation(failed.path, null, null),
        )

    private companion object {
        private val logger = LoggerFactory.getLogger(DeploymentPreparer::class.java)
    }
}
