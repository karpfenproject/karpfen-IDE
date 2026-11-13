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
import io.karpfen.ide.runtime.protocol.catalogue.FeatureName
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
 * How a deployment ends and whether it brings a debugger along. Immutable.
 *
 * @property start true to start the environment after activating it. Otherwise it stays activated and ready to start.
 * @property observe true to switch observation on as part of the deployment. Otherwise the model is read back once.
 * @property debugger true to switch on every runtime feature the plan lists, which includes `Breakpoint` when the
 *   project has breakpoints switched on, so the session offers a debugger. False leaves `Breakpoint` off whatever the
 *   plan lists. The engine then reads the breakpoints of the state machines and never halts at them, and the session
 *   has no debugger. The other runtime features of the plan are switched on either way.
 */
public data class DeployOptions(
    public val start: Boolean = true,
    public val observe: Boolean = true,
    public val debugger: Boolean = true,
) {
    /**
     * [plan] as a deployment with these options carries it out. It is [plan] itself with [debugger] on, and [plan]
     * without the runtime feature `Breakpoint` with [debugger] off. Launching a session and building the deploy steps
     * both go through this function, so the steps and the session's debugger always agree.
     */
    public fun applyTo(plan: DeploymentPlan): DeploymentPlan = if (debugger) plan else plan.withoutFeature(FeatureName.BREAKPOINT)
}

/**
 * Hears what a deployment does, while it does it. The deployer calls it in the deploying coroutine. An implementation
 * returns quickly and does not suspend.
 */
public interface DeployListener {
    /** The progress changed, because a step started, reported a detail or ended. */
    public fun progress(progress: DeployProgress)

    /** The environment was created. From now on it exists on the runtime. */
    public fun environmentCreated(environment: RuntimeEnvironment)

    /** The connection exists and is about to be opened, so its events can be collected from its first opening on. */
    public fun connected(connection: RuntimeConnection)
}

/**
 * The port through which a deployment reaches the session's observation without depending on it. The session
 * implements it over its observation of the model.
 *
 * The deployer calls it from the deploying coroutine, one call at a time.
 *
 * Failures are returned as outcomes, never thrown. A `CancellationException` of the caller is rethrown.
 */
public interface DeployObservation {
    /** Switches observation on. It subscribes to the whole model and returns the snapshot it starts from. */
    public suspend fun switchOn(): CallOutcome<RuntimeModelSnapshot>

    /** Reads a snapshot of the model once and applies it. */
    public suspend fun pull(): CallOutcome<RuntimeModelSnapshot>

    /**
     * How the model in [snapshot], as the runtime read it, differs from the model the IDE analyzed. The result holds
     * one warning per differing object, located at the object's declaration.
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
 * @property connectionScope the scope the connection runs in. The connection runs in a supervisor child of it.
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
 * The waits of a deployment, and the bounds of stopping and disconnecting an environment. The bounds apply in the
 * cleanup of a failed deployment and in the stop of a session. Immutable.
 *
 * @property connectionWait how long activating and starting wait for the connection to be open.
 * @property confirmMinimum the least time the start confirmation waits for every state machine to report a state.
 * @property confirmTicks the number of ticks the start confirmation waits at least, if they take longer than
 *   [confirmMinimum].
 * @property confirmPoll how often the start confirmation reads the model at most. It never reads more often than once
 *   a tick.
 * @property stopBound how long stopping an environment may take before it counts as unanswered. Keep it above the
 *   client's stop timeout plus its snapshot timeout. The snapshot timeout bounds the probe that may follow an
 *   unanswered stop. With a large enough bound, a stop that the client still sees succeed is never cut off. With the
 *   client's default timeouts that sum is 60 seconds. A client with longer timeouts needs a longer bound.
 * @property disconnectBound how long letting a connection end after a stop, and disconnecting it, may take each.
 */
public data class DeployTiming(
    public val connectionWait: Duration = 10.seconds,
    public val confirmMinimum: Duration = 3.seconds,
    public val confirmTicks: Int = 20,
    public val confirmPoll: Duration = 250.milliseconds,
    public val stopBound: Duration = 75.seconds,
    public val disconnectBound: Duration = 15.seconds,
) {
    init {
        require(connectionWait.isPositive()) { "the connection wait is positive, not $connectionWait" }
        require(!confirmMinimum.isNegative()) { "the confirmation minimum is never negative, not $confirmMinimum" }
        require(confirmTicks >= 0) { "the confirmation ticks are never negative, not $confirmTicks" }
        require(confirmPoll.isPositive()) { "the confirmation poll is positive, not $confirmPoll" }
        require(stopBound.isPositive()) { "the stop bound is positive, not $stopBound" }
        require(disconnectBound.isPositive()) { "the disconnect bound is positive, not $disconnectBound" }
    }
}

/**
 * Why a deployment failed. Immutable.
 *
 * @property step the step that failed, with its final status and, as its detail, the failure in one sentence.
 * @property problems the problems the failure is, each located where it can be. There is at least one. An upload the
 *   runtime refused for its content has one problem per error, each at its place in the uploaded file, in the order of
 *   the text. Every other failure is one problem.
 * @property failure the runtime's failure behind it, or null if the deployment failed for another reason, such as an
 *   ended connection.
 */
public data class DeployFailure(
    public val step: StepProgress,
    public val problems: List<DeployProblem>,
    public val failure: RuntimeFailure?,
) {
    init {
        require(problems.isNotEmpty()) { "a failed deployment has at least one problem" }
    }
}

/** How a deployment ended. */
public sealed interface DeploymentResult {
    /**
     * The environment is deployed and activated, and started if the options asked for it.
     *
     * @property environment its handle.
     * @property connection its open connection. The session owns it from now on.
     * @property findings the warnings of the deployment itself. These are the differences of the model the runtime
     *   read back, and a start that was not confirmed in time. The plan's own findings stay on the plan.
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
 * [deploy] runs in the caller's coroutine and suspends until the deployment ended. Every wait is a coroutine delay or
 * timeout on the caller's dispatcher.
 *
 * A failure of the runtime is returned as a [DeploymentResult.Failed], never thrown. The deployment stops at the first
 * failing step and cleans up according to the phase that the failure left. On cancellation the cleanup runs first. It
 * cannot be cancelled and is bounded in time. Then the `CancellationException` is rethrown.
 */
public fun interface Deployer {
    /** Runs [request] and reports the progress, the created environment and the connection to [listener]. */
    public suspend fun deploy(request: DeployRequest, listener: DeployListener): DeploymentResult
}

/**
 * Creates the deployer. It records what stays on a runtime in [ledger] and hands environments that stay to
 * [disposer]. By default they are kept and recorded, because the runtime cannot delete environments.
 */
public fun Deployer(
    ledger: EnvironmentLedger,
    timing: DeployTiming = DeployTiming(),
    disposer: EnvironmentDisposer = KeepEnvironments(ledger),
): Deployer = DefaultDeployer(timing, EnvironmentCleanup(ledger, disposer, CleanupBounds.of(timing)))
