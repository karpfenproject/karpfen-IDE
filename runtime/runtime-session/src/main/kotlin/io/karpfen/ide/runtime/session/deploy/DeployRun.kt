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

import io.karpfen.ide.runtime.protocol.api.Reach
import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.connection.RuntimeConnection
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.session.plan.DeploymentPlan
import io.karpfen.ide.runtime.session.plan.PlannedUpload

/**
 * The state of one deployment while it runs. It holds the request, what was created so far and the progress. The steps
 * read and record through it. The cleanup reads what was created. Confined to the deploying coroutine.
 *
 * @property request the deployment.
 * @property timing the waits of the deployment.
 * @param listener who hears the progress, the environment and the connection.
 * @param board the progress.
 * @param locator turns failures into located problems.
 */
internal class DeployRun(
    val request: DeployRequest,
    val timing: DeployTiming,
    private val listener: DeployListener,
    private val board: ProgressBoard,
    private val locator: FailureLocator,
) {
    /** The plan being deployed. */
    val plan: DeploymentPlan
        get() = request.plan

    /** The runtime deployed to. */
    val endpoint: RuntimeEndpoint
        get() = request.client.endpoint

    /** The created environment, or null before it was created. */
    var environment: RuntimeEnvironment? = null
        private set

    /** The connection to the environment, or null before it exists. */
    var connection: RuntimeConnection? = null
        private set

    /** The index of the step running now. */
    private var stepIndex: Int = -1

    /** True while the creation call runs. */
    private var creating: Boolean = false

    /** True once a creation call ended without telling whether it created an environment. */
    private var creationLeftOpen: Boolean = false

    /**
     * True if an environment may have been created without the deployment knowing its key. That is the case while the
     * creation call runs, and after a creation call that failed in a way that may still have applied it.
     */
    val creationUnknown: Boolean
        get() = environment == null && (creating || creationLeftOpen)

    /** The created environment. */
    val createdEnvironment: RuntimeEnvironment
        get() = checkNotNull(environment) { "the environment is created before any step that uses it" }

    /** The kind of the step running now. */
    private val stepKind: DeployStepKind
        get() = board.step(stepIndex).kind

    /** Marks the step at [index] as running. */
    fun enter(index: Int) {
        stepIndex = index
        board.update(index, StepStatus.RUNNING, null)
    }

    /** Reports what the running step is doing, as its [detail]. */
    fun report(detail: String) {
        board.update(stepIndex, StepStatus.RUNNING, detail)
    }

    /** Marks that the creation call starts. */
    fun creationStarts() {
        creating = true
    }

    /** Records the created [environment] and reports it. */
    fun created(environment: RuntimeEnvironment) {
        creating = false
        this.environment = environment
        listener.environmentCreated(environment)
        board.environment(environment.key)
    }

    /** Records that the creation failed with [failure], which may have left an environment behind. */
    fun creationFailed(failure: RuntimeFailure) {
        creating = false
        creationLeftOpen = failure.reach == Reach.MAYBE_APPLIED
    }

    /** Records the [connection] and reports it before it is opened. */
    fun connected(connection: RuntimeConnection) {
        this.connection = connection
        listener.connected(connection)
    }

    /** The failed outcome of the running step for [failure], located at [upload] if the step uploads it. */
    fun failed(failure: RuntimeFailure, upload: PlannedUpload? = null): StepOutcome.Failed =
        StepOutcome.Failed(locator.locate(stepKind, upload, failure), failure)

    /**
     * The outcome of the running step for the call [outcome]. A call that succeeded leads to [done] with its value. A
     * failed call leads to the located failure. A refusal of the handle fails the step too.
     */
    fun <T> settle(outcome: CallOutcome<T>, upload: PlannedUpload? = null, done: (T) -> StepOutcome = { StepOutcome.Done() }): StepOutcome =
        when (outcome) {
            is CallOutcome.Done -> done(outcome.value)
            is CallOutcome.Failed -> failed(outcome.failure, upload)
            is CallOutcome.Refused -> StepOutcome.Failed(locator.refused(outcome.refusal), null)
        }
}
