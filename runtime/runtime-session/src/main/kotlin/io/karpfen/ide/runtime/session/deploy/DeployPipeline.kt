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
package io.karpfen.ide.runtime.session.deploy

import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.session.plan.DeploymentPlan
import io.karpfen.ide.runtime.session.plan.UploadRole
import io.karpfen.ide.runtime.session.problem.DeployProblem
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * The steps of deploying a plan, in the order they run. The deployment checks the runtime, creates the environment and
 * connects. It uploads the metamodel, the event definitions and the model, and attaches each state machine in
 * descriptor order. It sets the tick delay and the event time to live, listens to the event domains and switches on the
 * runtime features the state machines need. Then it activates, observes, starts and confirms the start. Steps that the plan or the options do not need are still present
 * and are skipped, so the progress always shows the same steps. Stateless.
 */
internal object DeploySteps {
    /** The steps of deploying [plan] to [endpoint] with [options]. */
    fun of(plan: DeploymentPlan, options: DeployOptions, endpoint: RuntimeEndpoint): List<DeployStep> =
        buildList {
            add(CheckRuntimeStep(endpoint))
            add(CreateEnvironmentStep())
            add(ConnectStep())
            add(UploadStep(plan.metamodel))
            add(plan.events?.let(::UploadStep) ?: SkippedStep(DeployStepKind.UPLOAD_EVENTS, "Uploading the event definitions", "The context has no event definitions"))
            add(UploadStep(plan.model))
            plan.uploads.filter { it.role == UploadRole.STATE_MACHINE }.forEach { add(AttachStep(it)) }
            add(TickDelayStep(plan.configuration.tickDelayMs))
            add(EventTtlStep(plan.configuration.eventTtlMs))
            add(
                if (plan.domains.isEmpty()) {
                    SkippedStep(DeployStepKind.LISTEN_DOMAINS, "Listening to the event domains", "The state machines use no event domain")
                } else {
                    ListenDomainsStep(plan.domains)
                },
            )
            add(
                if (plan.features.isEmpty()) {
                    SkippedStep(DeployStepKind.SWITCH_FEATURES_ON, "Switching on runtime features", "The state machines need no runtime feature")
                } else {
                    SwitchFeaturesOnStep(plan.features)
                },
            )
            add(ActivateStep())
            add(ObserveStep(options.observe))
            if (options.start) {
                add(StartStep())
                add(ConfirmStartStep(plan.configuration.tickDelayMs))
            } else {
                add(SkippedStep(DeployStepKind.START, "Starting the environment", "The deployment leaves the environment ready to start"))
                add(SkippedStep(DeployStepKind.CONFIRM_START, "Confirming the start", "The environment is not started"))
            }
        }
}

/**
 * Runs the steps of one deployment one by one, publishes progress after every transition, and stops at the first
 * failure. After a failure, and after a cancellation, it hands the run to the [cleanup]. A cancellation is rethrown
 * once the cleanup is done. So is an exception that a step should never have thrown.
 *
 * @param steps the steps, in order.
 * @param cleanup cleans up after a failure or a cancellation.
 */
internal class DeployPipeline(private val steps: List<DeployStep>, private val cleanup: EnvironmentCleanup) {

    /** Runs the steps in [run], whose progress is on [board]. */
    suspend fun run(run: DeployRun, board: ProgressBoard): DeploymentResult {
        board.publish()
        val findings = mutableListOf<DeployProblem>()
        steps.forEachIndexed { index, step ->
            run.enter(index)
            val outcome = try {
                step.execute(run)
            } catch (thrown: Throwable) {
                val cancelled = thrown is CancellationException
                board.update(index, StepStatus.FAILED, if (cancelled) "Cancelled" else "Failed unexpectedly: $thrown")
                logger.info("Deployment of {} ended at '{}' by {}; cleaning up", run.plan.name, step.title, thrown.toString())
                cleanup.clean(run)
                throw thrown
            }
            when (outcome) {
                is StepOutcome.Done -> {
                    findings += outcome.findings
                    board.update(index, StepStatus.DONE, outcome.detail)
                }
                is StepOutcome.Skipped -> board.update(index, StepStatus.SKIPPED, outcome.detail)
                is StepOutcome.Failed -> {
                    board.update(index, StepStatus.FAILED, outcome.problem.message)
                    logger.info("Deployment of {} failed at '{}': {}", run.plan.name, step.title, outcome.problem.message)
                    val failure = DeployFailure(board.step(index), outcome.problem, outcome.failure)
                    return DeploymentResult.Failed(failure, cleanup.clean(run))
                }
            }
        }
        val environment = run.createdEnvironment
        val connection = checkNotNull(run.connection) { "a deployment that ran every step has a connection" }
        logger.info("Deployed {} as {}", run.plan.name, environment.key)
        return DeploymentResult.Deployed(environment, connection, findings.toList())
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(DeployPipeline::class.java)
    }
}

/**
 * The default deployer. It builds the steps of each request and runs them in a [DeployPipeline] in the caller's
 * coroutine.
 *
 * @param timing the waits of every deployment.
 * @param cleanup cleans up after a failed or cancelled deployment.
 */
internal class DefaultDeployer(private val timing: DeployTiming, private val cleanup: EnvironmentCleanup) : Deployer {
    override suspend fun deploy(request: DeployRequest, listener: DeployListener): DeploymentResult {
        val steps = DeploySteps.of(request.plan, request.options, request.client.endpoint)
        val board = ProgressBoard(steps, listener)
        val run = DeployRun(request, timing, listener, board, FailureLocator(request.plan, request.client.endpoint))
        return DeployPipeline(steps, cleanup).run(run, board)
    }
}
