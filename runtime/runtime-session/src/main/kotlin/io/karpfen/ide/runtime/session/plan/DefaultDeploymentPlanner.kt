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

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.analysis.ContextSymbols
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.runtime.session.problem.DeployProblem
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Plans a deployment in two stages. The [gate] checks run first. Only a subject without an error there goes through
 * the [preflight] checks. A subject without an error in either stage becomes a plan, built from the analyzed snapshots
 * that the gate read.
 *
 * The plan is built from these inputs. The context is the one whose descriptor is the given path, and the plan takes
 * the typed descriptor of that context. The analyzed snapshots of the context's files are the texts to upload. The
 * runtime's index of the dsl-tools model gives the object keys and the types of the attached objects. The syntax index
 * gives the event domains of the machines and the declaration ranges of the uploaded files. The state machines that the
 * analysis read tell the runtime features the deployment switches on, as [LanguageFeature] decides.
 *
 * @param gate the checks of the problems gate, in report order.
 * @param preflight the preflight checks, in report order.
 */
internal class DefaultDeploymentPlanner(
    private val gate: List<PlanCheck>,
    private val preflight: List<PlanCheck>,
) : DeploymentPlanner {

    override fun plan(analysis: ProjectAnalysis, descriptor: Path, unsaved: Set<Path>): PlanResult {
        val subject = PlanSubject(analysis, normalized(descriptor), unsaved.mapTo(HashSet(), ::normalized))
        val gateFindings = gate.flatMap { it.check(subject) }
        if (gateFindings.hasError()) return blocked(subject, gateFindings)
        val findings = gateFindings + preflight.flatMap { it.check(subject) }
        if (findings.hasError()) return blocked(subject, findings)
        return PlanResult.Ready(planOf(subject, findings))
    }

    /** The plan of [subject], which passed every check, with the [findings] of the checks. */
    private fun planOf(subject: PlanSubject, findings: List<DeployProblem>): DeploymentPlan {
        val context = checkNotNull(subject.context) { "a subject that passed the gate has a context" }
        val environment = checkNotNull(subject.environment) { "a subject that passed the gate has a descriptor" }
        val keyed = checkNotNull(subject.keyedObjects) { "a subject that passed the gate has a model" }
        val modelingContext = context.context
        fun upload(role: UploadRole, file: Path, attachedTo: String? = null): PlannedUpload {
            val analyzed = checkNotNull(context.files[file]) { "a subject that passed the gate has every file analyzed" }
            return PlannedUpload(role, file, analyzed.snapshot, attachedTo)
        }
        val machines = subject.machines.map { machine ->
            upload(UploadRole.STATE_MACHINE, machine.file, checkNotNull(machine.attachedTo) { "an explicit context attaches every machine" })
        }
        val symbols = ContextSymbols(subject.analysis)
        val attachments = machines.map { machine ->
            val key = checkNotNull(machine.attachedTo)
            AttachedMachine(
                attachedTo = key,
                file = machine.file,
                objectType = keyed.typeOf(key),
                machineType = context.stateMachines[machine.file]?.attachedToClass ?: symbols.attachedTypeOf(machine.file).orEmpty(),
            )
        }
        val uploads = listOfNotNull(
            upload(UploadRole.METAMODEL, checkNotNull(modelingContext.metamodel)),
            modelingContext.events?.let { upload(UploadRole.EVENTS, it) },
            upload(UploadRole.MODEL, checkNotNull(subject.modelFile)),
        ) + machines
        return DeploymentPlan.Builder()
            .name(modelingContext.name)
            .descriptor(subject.descriptor)
            .generation(subject.analysis.generation)
            .uploads(uploads)
            .attachments(attachments)
            .configuration(DeployConfiguration.of(environment))
            .domains(subject.domains)
            .features(LanguageFeature.requiredBy(machines.mapNotNull { context.stateMachines[it.file] }))
            .modelObjectIds(keyed.ids)
            .locations(subject.locations)
            .findings(findings)
            .build()
    }

    /** The blocked result with [findings], logged. */
    private fun blocked(subject: PlanSubject, findings: List<DeployProblem>): PlanResult.Blocked {
        logger.info("Deployment of {} blocked: {}", subject.descriptor, findings.filter { it.severity == Severity.ERROR }.map { it.code })
        return PlanResult.Blocked(findings)
    }

    /** True if one of the findings is an ERROR. */
    private fun List<DeployProblem>.hasError(): Boolean = any { it.severity == Severity.ERROR }

    /** [path] as the workspace keeps paths, which is absolute and normalized. */
    private fun normalized(path: Path): Path = path.toAbsolutePath().normalize()

    private companion object {
        private val logger = LoggerFactory.getLogger(DefaultDeploymentPlanner::class.java)
    }
}
