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
import io.karpfen.ide.runtime.protocol.connection.ConnectionEnd
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.connection.RuntimeConnection
import io.karpfen.ide.runtime.protocol.connection.SendOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleRefusal
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.protocol.lifecycle.Uploads
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.session.deploy.DeployProgress
import io.karpfen.ide.runtime.session.deploy.DeploymentResult
import io.karpfen.ide.runtime.session.deploy.LedgerDisposition
import io.karpfen.ide.runtime.session.deploy.LedgerEntry
import io.karpfen.ide.runtime.session.deploy.StepStatus
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * The conductor of a session, a mediator between the session's callers, its deployment, its environment and its
 * router: it takes the commands and the reports of the deployment, the environment's handle, the router and its own
 * stop from one unbounded inbox, one at a time, in arrival order, and answers commands through their replies. It is the
 * single writer of the session's state, progress and problems.
 *
 * - **Deployment.** [SessionDeployment] runs it on a coroutine of its own and reports here, so commands are answered
 *   while it runs (refused, except shutting down, which waits for its end).
 * - **State.** Once the deployment ended, the state follows the environment's phase ([PhaseStates]); the conductor
 *   follows the handle's phase and settles the state after every command at once.
 * - **Commands** ([CommandRules]) are carried out one by one through [SessionCalls]; only the stop runs on its own, in
 *   [EnvironmentStopper], so that the session shows [SessionState.Stopping] and refuses commands meanwhile.
 * - **Connection endings** after the deployment ([ConnectionEndings]): a refused handshake fails the session and stops
 *   the environment; a stop the session did not make is settled by reading the final model; a lost environment fails
 *   the session; a connection that gave up reconnecting is a warning.
 * - **Events** go through the inbox like commands, so an event sent after a stop is refused.
 *
 * The router never waits for the conductor; the conductor waits for the router's answers to observation calls.
 *
 * @param startsEnvironment true if the deployment starts the environment.
 * @param router the session's router.
 * @param inbox the conductor's inbox.
 * @param status the session's published status.
 * @param departure records the environment once and settles a close that races the deployment.
 * @param calls makes the calls of the commands and events.
 * @param stopper stops the environment on its own coroutine.
 * @param clock the session's clock.
 * @param scope the session's scope.
 */
internal class SessionConductor(
    private val startsEnvironment: Boolean,
    private val router: PushRouter,
    private val inbox: ConductorInbox,
    private val status: SessionStatus,
    private val departure: EnvironmentDeparture,
    private val calls: SessionCalls,
    private val stopper: EnvironmentStopper,
    private val clock: SessionClock,
    private val scope: CoroutineScope,
) {
    /** Completed when the session is closed, so callers stop waiting for replies. */
    private val closed = CompletableDeferred<Unit>()

    /** The environment once the deployment created it; read by callers whose event is refused. */
    private val created = AtomicReference<RuntimeEnvironment?>(null)

    /** The connection once the deployment succeeded. */
    private var connection: RuntimeConnection? = null

    /** True while the connection is open. */
    private var linkOpen: Boolean = false

    /** The phase the state was last settled from. */
    private var settledPhase: EnvironmentPhase? = null

    /** The end of the connection that came while the deployment ran, handled once it ended. */
    private var endWhileDeploying: ConnectionEnd? = null

    /** The shutdowns that wait for the deployment to end. */
    private val shutdownsAfterDeployment = ArrayList<CompletableDeferred<Unit>>()

    /** The stop that runs, or null. */
    private var stop: StopInProgress? = null

    /** True once the session knows its environment is stopped and read its final model. */
    private var finalized: Boolean = false

    /** Starts taking messages in the session's scope. */
    fun start() {
        scope.launch(CoroutineName("session-conductor")) {
            for (message in inbox.messages) take(message)
        }
    }

    /** Carries out [command] in order and answers its outcome; refused once the session is closed. */
    suspend fun execute(command: SessionCommand): SessionCommandOutcome {
        val reply = CompletableDeferred<SessionCommandOutcome>()
        if (!inbox.offer(ConductorMessage.Command(command, reply))) return CLOSED
        return select {
            reply.onAwait { it }
            closed.onAwait { CLOSED }
        }
    }

    /** Sends [frame] in order with the commands and answers how it went; refused once the session is closed. */
    suspend fun inject(frame: EventFrame): SendOutcome {
        val reply = CompletableDeferred<SendOutcome>()
        if (!inbox.offer(ConductorMessage.Inject(frame, reply))) return notAllowed(CLOSED.reason)
        return select {
            reply.onAwait { it }
            closed.onAwait { notAllowed(CLOSED.reason) }
        }
    }

    /** Waits for a running deployment, stops the environment if it is activated, running or halted, and returns. */
    suspend fun shutdown() {
        val reply = CompletableDeferred<Unit>()
        if (!inbox.offer(ConductorMessage.Shutdown(reply))) return
        select {
            reply.onAwait { }
            closed.onAwait { }
        }
    }

    /**
     * Closes the conductor without suspending: seals the state as closed, answers every waiting caller and, if the
     * deployment succeeded, closes the connection and records the environment as it is left. A deployment that still
     * runs is cancelled with the session's scope and cleans up itself.
     */
    fun close() {
        status.seal()
        closed.complete(Unit)
        inbox.close()
        val leaving = departure.close() ?: return
        val environment = leaving.deployed.environment
        leaving.deployed.connection.close()
        if (leaving.records) departure.leave(environment.key, environment.phase.value, "Left on the runtime when the session was closed.")
    }

    /** Takes one [message]; an exception it should never have thrown fails the reply it carries and is logged. */
    private suspend fun take(message: ConductorMessage) {
        try {
            when (message) {
                is ConductorMessage.Command -> command(message)
                is ConductorMessage.Inject -> message.reply.complete(send(message.frame))
                is ConductorMessage.Shutdown -> shutdown(message.reply)
                is ConductorMessage.Progressed -> progressed(message.progress)
                is ConductorMessage.EnvironmentCreated -> environmentCreated(message.environment)
                is ConductorMessage.DeployEnded -> deployEnded(message.result)
                is ConductorMessage.DeployBroke -> deployBroke(message.error)
                is ConductorMessage.PhaseChanged -> if (status.state.value != SessionState.Deploying) settle(environment().phase.value)
                is ConductorMessage.ConnectionEnded -> connectionEnded(message.end)
                is ConductorMessage.ConnectionChanged -> connectionChanged(message.state)
                is ConductorMessage.ObservationProblem -> raise(message.problem)
                is ConductorMessage.StopFinished -> stopFinished(message.outcome, message.leftBehind)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (broken: Exception) {
            logger.error("The conductor of a session failed on {}", message, broken)
            message.reply?.completeExceptionally(broken)
        }
    }

    /** Carries out the command of [message] if the state allows it, and settles the state from the phase afterwards. */
    private suspend fun command(message: ConductorMessage.Command) {
        val state = status.state.value
        CommandRules.refusal(message.command, state)?.let { reason ->
            message.reply.complete(SessionCommandOutcome.Refused(state, reason))
            return
        }
        val environment = environment()
        if (message.command == SessionCommand.Stop) {
            beginStop(StopWaiter.Command(message.reply), failure = null)
            return
        }
        val outcome = calls.perform(message.command, environment)
        settle(environment.phase.value)
        message.reply.complete(outcome)
    }

    /** Sends [frame] if the state allows injecting events. */
    private suspend fun send(frame: EventFrame): SendOutcome {
        CommandRules.injectionRefusal(status.state.value)?.let { return notAllowed(it) }
        return calls.send(frame, connection)
    }

    /** The refusal of an event, for [reason], in the environment's phase. */
    private fun notAllowed(reason: String): SendOutcome =
        SendOutcome.NotAllowed(
            LifecycleRefusal(LifecycleOperation.INJECT_EVENT, created.get()?.phase?.value ?: EnvironmentPhase.Configuring(Uploads.NONE), reason),
        )

    /** Ends the session cleanly once nothing is left to stop: after the deployment, after a stop, or at once. */
    private fun shutdown(reply: CompletableDeferred<Unit>) {
        stop?.let { running ->
            running.waiters += StopWaiter.Shutdown(reply)
            return
        }
        when (status.state.value) {
            SessionState.Deploying -> shutdownsAfterDeployment += reply
            is SessionState.Ready, is SessionState.Running, is SessionState.Halted -> beginStop(StopWaiter.Shutdown(reply), failure = null)
            else -> reply.complete(Unit)
        }
    }

    /**
     * Starts stopping the environment, with [waiter] waiting for the end; [failure] is the problem the session failed
     * with, if it stops because of a failure and keeps its failed state.
     */
    private fun beginStop(waiter: StopWaiter, failure: DeployProblem?) {
        val environment = environment()
        stop = StopInProgress(status.state.value, failure, mutableListOf(waiter))
        if (failure == null) publish(SessionState.Stopping(environment.key))
        stopper.launch(environment, connection)
    }

    /**
     * Takes the end of the session's own stop, which recorded the environment as [leftBehind] if it stopped it: the
     * waiters hear how it went, and the state follows.
     */
    private fun stopFinished(outcome: CallOutcome<Unit>?, leftBehind: LedgerEntry?) {
        val running = checkNotNull(stop) { "a stop finished that was never started" }
        stop = null
        val key = environment().key
        val result = when (outcome) {
            is CallOutcome.Done -> {
                finalized = true
                settledPhase = EnvironmentPhase.Stopped
                publish(running.failure?.let { SessionState.Failed(it, leftBehind) } ?: SessionState.Stopped(key))
                SessionCommandOutcome.Done
            }
            is CallOutcome.Refused -> {
                restore(running)
                SessionCommandOutcome.Refused(status.state.value, outcome.refusal.reason)
            }
            is CallOutcome.Failed -> calls.failed(outcome.failure).also { restore(running) }
            null -> {
                val problem = DeployProblem(
                    Severity.ERROR,
                    DeployProblemCodes.RUNTIME_NO_ANSWER,
                    "Stopping $key took too long; the environment may still run.",
                    null,
                )
                raise(problem)
                restore(running)
                SessionCommandOutcome.Failed(problem)
            }
        }
        running.waiters.forEach { it.answer(result) }
    }

    /** The state after a stop that did not stop the environment: as the phase says, or as before the stop. */
    private fun restore(running: StopInProgress) {
        val failure = running.failure
        if (failure != null) {
            publish(SessionState.Failed(failure, null))
            return
        }
        publish(running.before)
        settledPhase = null
        settle(environment().phase.value)
    }

    /** Publishes the deployment's [progress] and journals every step that ended since the last one. */
    private fun progressed(progress: DeployProgress) {
        val before = status.progress.value.steps
        progress.steps.forEachIndexed { index, step ->
            val ended = step.status == StepStatus.DONE || step.status == StepStatus.SKIPPED || step.status == StepStatus.FAILED
            if (ended && before.getOrNull(index)?.status != step.status) record(SessionActivity.StepFinished(step, clock.elapsed()))
        }
        status.progress(progress)
    }

    /** Takes the created [environment] and starts following its phase. */
    private fun environmentCreated(environment: RuntimeEnvironment) {
        created.set(environment)
        scope.launch(CoroutineName("session-phase")) {
            environment.phase.collect { inbox.offer(ConductorMessage.PhaseChanged(it)) }
        }
    }

    /** Takes the end of the deployment. */
    private suspend fun deployEnded(result: DeploymentResult) {
        when (result) {
            is DeploymentResult.Deployed -> deployed(result)
            is DeploymentResult.Failed -> {
                raise(result.failure.problem)
                result.cleanup.leftBehind?.leftBehind()?.let(::raise)
                publish(SessionState.Failed(result.failure.problem, result.cleanup.leftBehind))
            }
        }
        shutdownsAfterDeployment.toList().also { shutdownsAfterDeployment.clear() }.forEach(::shutdown)
    }

    /** Takes a deployment that succeeded: its findings, its state from the phase, and a connection that ended meanwhile. */
    private suspend fun deployed(result: DeploymentResult.Deployed) {
        connection = result.connection
        result.findings.forEach(::raise)
        val environment = result.environment
        created.set(environment)
        val phase = environment.phase.value
        when (val verdict = PhaseStates.of(phase, environment.key)) {
            is PhaseVerdict.Keeps -> {
                publish(if (startsEnvironment) SessionState.Running(environment.key) else SessionState.Ready(environment.key))
                settledPhase = phase
                verdict.problem?.let(::raise)
            }
            else -> settle(phase)
        }
        endWhileDeploying?.let { end ->
            endWhileDeploying = null
            connectionEnded(end)
        }
    }

    /** Takes a deployment that broke: the session fails. */
    private fun deployBroke(error: Throwable) {
        val problem = DeployProblem(Severity.ERROR, DeployProblemCodes.RUNTIME_ERROR, "The deployment failed unexpectedly: $error", null)
        raise(problem)
        publish(SessionState.Failed(problem, null))
        shutdownsAfterDeployment.toList().also { shutdownsAfterDeployment.clear() }.forEach { it.complete(Unit) }
    }

    /** Settles the state from the environment's [phase], unless the phase is the one the state was settled from. */
    private fun settle(phase: EnvironmentPhase) {
        val state = status.state.value
        if (state is SessionState.Stopping || state is SessionState.Failed || state == SessionState.Closed || phase == settledPhase) return
        settledPhase = phase
        when (val verdict = PhaseStates.of(phase, environment().key)) {
            is PhaseVerdict.Becomes -> if (verdict.state != state) {
                publish(verdict.state)
                verdict.problem?.let(::raise)
            }
            is PhaseVerdict.Keeps -> verdict.problem?.let(::raise)
            is PhaseVerdict.Loses -> lose(verdict.problem)
        }
    }

    /** Takes the end of the connection, unless the deployment still runs or the session ended it or its environment. */
    private suspend fun connectionEnded(end: ConnectionEnd) {
        val state = status.state.value
        when {
            state == SessionState.Deploying -> endWhileDeploying = end
            stop != null || finalized || state is SessionState.Failed || state == SessionState.Closed -> Unit
            else -> when (val verdict = ConnectionEndings.of(end, environment().key)) {
                is EndingVerdict.FailsAndStops -> {
                    raise(verdict.problem)
                    publish(SessionState.Failed(verdict.problem, null))
                    beginStop(StopWaiter.None, failure = verdict.problem)
                }
                EndingVerdict.StoppedElsewhere -> finalizeStop()
                is EndingVerdict.Loses -> lose(verdict.problem)
                is EndingVerdict.Warns -> raise(verdict.problem)
                EndingVerdict.Ignored -> Unit
            }
        }
    }

    /** Settles a stop the session did not make: reads the final model, lets the connection end and records the environment. */
    private suspend fun finalizeStop() {
        finalized = true
        val environment = environment()
        router.environmentStopped()
        connection?.let { stopper.letEnd(it) }
        departure.record(environment.key, LedgerDisposition.STOPPED, "Stopped by somebody else than the session.")
        settledPhase = EnvironmentPhase.Stopped
        publish(SessionState.Stopped(environment.key))
        record(SessionActivity.Note("The environment ${environment.key} was stopped by somebody else than the session.", clock.elapsed()))
    }

    /** The session lost its environment for good: it fails with [problem] and records it as lost. */
    private fun lose(problem: DeployProblem) {
        if (status.state.value is SessionState.Failed) return
        departure.record(environment().key, LedgerDisposition.LOST, "The runtime no longer knows the environment.")
        raise(problem)
        publish(SessionState.Failed(problem, null))
        connection?.close()
    }

    /** Takes the connection's new [state] for the injection availability. */
    private fun connectionChanged(state: ConnectionState) {
        linkOpen = state is ConnectionState.Open
        publish(status.state.value)
    }

    /** Publishes [state] with the injection availability that follows from it. */
    private fun publish(state: SessionState) {
        status.publish(state, InjectionAvailabilities.of(state, linkOpen))
    }

    /** Adds [problem] to the session's problems and journals it. */
    private fun raise(problem: DeployProblem) {
        status.raise(problem)
        record(SessionActivity.ProblemRaised(problem, clock.elapsed()))
    }

    /** Records [activity] in the journal, through the router, its one writer. */
    private fun record(activity: SessionActivity) {
        router.record(activity)
    }

    /** The environment, which every command after the deployment has. */
    private fun environment(): RuntimeEnvironment = checkNotNull(created.get()) { "the environment exists once the deployment ended" }

    private companion object {
        /** The conductor's logger. */
        private val logger = LoggerFactory.getLogger(SessionConductor::class.java)

        /** The answer to a command of a closed session. */
        private val CLOSED = SessionCommandOutcome.Refused(SessionState.Closed, "The session is closed.")
    }
}
