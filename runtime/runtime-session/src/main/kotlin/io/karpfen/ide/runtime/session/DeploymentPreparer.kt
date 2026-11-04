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
import io.karpfen.ide.runtime.session.states.MachineLayout
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Prepares the deployment of one environment descriptor from a workspace, so that what the problems gate checked is
 * exactly what runs:
 *
 * 1. saves every open document, because a deployment uploads the saved files; a failed save blocks with
 *    `deploy-save-failed`;
 * 2. awaits the analysis of the saved state;
 * 3. finds the unsaved paths: open documents that are still dirty, or whose analyzed snapshot is not their current
 *    one (another lineage or version), both because they were edited while the analysis ran;
 * 4. plans the deployment with [planner];
 * 5. turns the analysis into immutable values: the schema of the domain metamodel, the seed of the model, one layout
 *    per attached machine and the event catalog of the plan's domains.
 *
 * Nothing the preparation answers holds a dsl-tools object; the dsl-tools objects of the analysis are read only here.
 * Stateless.
 *
 * @param planner plans the deployment from the analysis.
 */
internal class DeploymentPreparer(private val planner: DeploymentPlanner) {

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
     * True if [document] differs from what the deployment would upload: it is dirty, or the analysis read another text
     * of it than its current one.
     */
    private fun isUnsaved(document: OpenDocument, analysis: ProjectAnalysis): Boolean {
        if (document.isDirty.value) return true
        val analyzed = analysis.file(document.path)?.snapshot ?: return false
        val current = document.session.text.snapshot.value
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
        val catalog = EventCatalogBuilder.build(
            domains = plan.domains,
            machines = machines,
            eventsMetamodel = context.events,
            eventTypeNames = plan.events?.let { ContextSymbols(analysis).typeNamesOf(it.file) }.orEmpty(),
            sources = EventSources(plan.metamodel.snapshot, plan.events?.snapshot),
        )
        return PreparedDeployment(
            plan = plan,
            schema = schema,
            seed = MirrorSeeder.seed(model, schema),
            layouts = machines.map { MachineLayout.of(it.attachedTo, it.file, it.machine) },
            catalog = catalog,
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
