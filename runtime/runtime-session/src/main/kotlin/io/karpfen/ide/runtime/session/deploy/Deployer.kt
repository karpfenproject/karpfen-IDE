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

import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.connection.RuntimeConnection
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.session.plan.DeploymentPlan
import io.karpfen.ide.runtime.session.problem.DeployProblem
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How a deployment ends. Immutable.
 *
 * @property start starts the environment after activating it; without, it stays activated and ready to start.
 * @property observe switches observation on as part of the deployment; without, the model is read back once.
 */
public data class DeployOptions(public val start: Boolean = true, public val observe: Boolean = true)

/**
 * Hears what a deployment does, while it does it. The deployer calls it in the deploying coroutine; an implementation
 * returns quickly and does not suspend.
 */
public interface DeployListener {
    /** The progress changed: a step started, reported a detail, or ended. */
    public fun progress(progress: DeployProgress)

    /** The environment was created; from now on it exists on the runtime. */
    public fun environmentCreated(environment: RuntimeEnvironment)

    /** The connection exists and is about to be opened, so its events can be collected from its first opening on. */
    public fun connected(connection: RuntimeConnection)
}

/**
 * The port through which a deployment reaches the session's observation without depending on it: the session
 * implements it over its observation of the model.
 *
 * Threading (MUST): the deployer calls it from the deploying coroutine, one call at a time.
 *
 * Contract (MUST): failures are outcomes, never exceptions; a `CancellationException` of the caller is rethrown.
 */
public interface DeployObservation {
    /** Switches observation on: subscribes to the whole model and answers the snapshot it starts from. */
    public suspend fun switchOn(): CallOutcome<RuntimeModelSnapshot>

    /** Reads a snapshot of the model once and applies it. */
    public suspend fun pull(): CallOutcome<RuntimeModelSnapshot>

    /**
     * How the model in [snapshot], as the runtime read it, differs from the model the IDE analyzed: warnings, one per
     * differing object, located at its declaration.
     */
    public fun differences(snapshot: RuntimeModelSnapshot): List<DeployProblem>
}

/**
 * One deployment to run. Immutable as far as its parts are.
 *
 * @property plan what to upload, set and register.
 * @property client the client of the runtime to deploy to.
 * @property clientId the client id the deployment registers listeners and connects as.
 * @property options how the deployment ends.
 * @property connectionScope the scope the connection runs in, a supervisor child of it.
 * @property observation the session's observation.
 */
public data class DeployRequest(
    public val plan: DeploymentPlan,
    public val client: RuntimeClient,
    public val clientId: ClientId,
    public val options: DeployOptions,
    public val connectionScope: CoroutineScope,
    public val observation: DeployObservation,
) {
    override fun toString(): String = "DeployRequest(plan=${plan.name}, runtime=${client.endpoint.httpBase}, clientId=$clientId, options=$options)"
}

/**
 * The waits of a deployment. Immutable.
 *
 * @property connectionWait how long activating and starting wait for the connection to be open.
 * @property confirmMinimum the least time the start confirmation waits for every state machine to report a state.
 * @property confirmTicks the number of ticks the start confirmation waits at least, if they take longer than
 *   [confirmMinimum].
 * @property confirmPoll how often the start confirmation reads the model at most; it never reads more often than once a
 *   tick.
 */
public data class DeployTiming(
    public val connectionWait: Duration = 10.seconds,
    public val confirmMinimum: Duration = 3.seconds,
    public val confirmTicks: Int = 20,
    public val confirmPoll: Duration = 250.milliseconds,
) {
    init {
        require(connectionWait.isPositive()) { "the connection wait is positive, not $connectionWait" }
        require(!confirmMinimum.isNegative()) { "the confirmation minimum is never negative, not $confirmMinimum" }
        require(confirmTicks >= 0) { "the confirmation ticks are never negative, not $confirmTicks" }
        require(confirmPoll.isPositive()) { "the confirmation poll is positive, not $confirmPoll" }
    }
}

/**
 * Why a deployment failed. Immutable.
 *
 * @property step the step that failed, with its final status.
 * @property problem the problem the failure is, located where it can be.
 * @property failure the runtime's failure behind it, or null if the deployment failed for another reason, such as an
 *   ended connection.
 */
public data class DeployFailure(
    public val step: StepProgress,
    public val problem: DeployProblem,
    public val failure: RuntimeFailure?,
)

/** How a deployment ended. */
public sealed interface DeploymentResult {
    /**
     * The environment is deployed, activated and, if asked, started.
     *
     * @property environment its handle.
     * @property connection its open connection, now the session's to own.
     * @property findings the warnings of the deployment itself: the differences of the model the runtime read back, and
     *   a start that was not confirmed in time. The plan's own findings stay on the plan.
     */
    public data class Deployed(
        public val environment: RuntimeEnvironment,
        public val connection: RuntimeConnection,
        public val findings: List<DeployProblem>,
    ) : DeploymentResult

    /**
     * The deployment failed and was cleaned up.
     *
     * @property failure why it failed.
     * @property cleanup what the cleanup did and what stays on the runtime.
     */
    public data class Failed(public val failure: DeployFailure, public val cleanup: CleanupReport) : DeploymentResult
}

/**
 * Deploys a plan to a runtime, step by step, and cleans up after a failure or a cancellation.
 *
 * Threading (MUST): [deploy] runs in the caller's coroutine and suspends until the deployment ended; every wait is a
 * coroutine delay or timeout on the caller's dispatcher.
 *
 * Contract (MUST):
 * - A failure of the runtime is a [DeploymentResult.Failed], never an exception; the deployment stops at the first
 *   failing step and cleans up by the phase the failure left.
 * - On cancellation the cleanup runs, not cancellable and bounded, and then the `CancellationException` is rethrown.
 */
public fun interface Deployer {
    /** Runs [request], reporting progress, the created environment and the connection to [listener]. */
    public suspend fun deploy(request: DeployRequest, listener: DeployListener): DeploymentResult
}

/**
 * The deployer, recording what stays on a runtime in [ledger] and handing environments that stay to [disposer]; by
 * default they are kept and recorded, since the runtime cannot delete environments.
 */
public fun Deployer(
    ledger: EnvironmentLedger,
    timing: DeployTiming = DeployTiming(),
    disposer: EnvironmentDisposer = KeepEnvironments(ledger),
): Deployer = DefaultDeployer(timing, EnvironmentCleanup(ledger, disposer, CleanupBounds.DEFAULT))
