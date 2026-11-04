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
import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
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
import io.karpfen.ide.runtime.session.deploy.CleanupBounds
import io.karpfen.ide.runtime.session.deploy.DeployListener
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import io.karpfen.ide.runtime.session.deploy.DeployProgress
import io.karpfen.ide.runtime.session.deploy.DeployRequest
import io.karpfen.ide.runtime.session.deploy.Deployer
import io.karpfen.ide.runtime.session.deploy.DeploymentResult
import io.karpfen.ide.runtime.session.deploy.FailureLocator
import io.karpfen.ide.runtime.session.deploy.LedgerDisposition
import io.karpfen.ide.runtime.session.deploy.LedgerEntry
import io.karpfen.ide.runtime.session.deploy.StepStatus
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * The conductor of a session, a mediator between the session's callers, its deployment, its environment and its
 * router: it runs the deployment, then carries out the commands and takes the reports of the deployment, the
 * environment's handle and the router from one unbounded inbox, one at a time, in arrival order, answering commands
 * through their replies. It is the single writer of the session's state, progress and problems.
 *
 * - **Deployment.** It runs in a coroutine of its own, so commands are answered while it runs (refused, except
 *   shutting down, which waits for its end). Its listener hands the progress and the created environment to the inbox
 *   and the connection, before it is opened, to the router.
 * - **State.** Once the deployment ended, the state follows the environment's phase ([PhaseStates]); the conductor
 *   collects the handle's phase and settles the state after every command at once.
 * - **Commands** ([CommandRules]) are carried out one by one; only the stop runs on its own, so that the session shows
 *   [SessionState.Stopping] and refuses commands meanwhile. A stop stops the environment (not cancellable, bounded),
 *   disconnects, has the router read the final model and records the environment as stopped.
 * - **Connection endings** after the deployment: a refused handshake fails the session and stops the environment; a
 *   stop the session did not make is settled by reading the final model; a lost environment fails the session; a
 *   connection that gave up reconnecting is a warning.
 * - **Events** go through the inbox like commands, so an event sent after a stop is refused.
 *
 * The router never waits for the conductor; the conductor waits for the router's answers to observation calls.
 *
 * @param prepared what the session deploys.
 * @param client the client of the runtime.
 * @param clientId the session's client id.
 * @param deployOptions how the deployment ends.
 * @param deployer runs the deployment.
 * @param router the session's router.
 * @param inbox the conductor's inbox.
 * @param status the session's published status.
 * @param departure records the environment once and settles a close that races the deployment.
 * @param clock the session's clock.
 * @param scope the session's scope.
 */
internal class SessionConductor(
    private val prepared: PreparedDeployment,
    private val client: RuntimeClient,
    private val clientId: ClientId,
    private val deployOptions: DeployOptions,
    private val deployer: Deployer,
    private val router: PushRouter,
    private val inbox: ConductorInbox,
    private val status: SessionStatus,
    private val departure: EnvironmentDeparture,
    private val clock: SessionClock,
    private val scope: CoroutineScope,
) {
    /** Completed when the session is closed, so callers stop waiting for replies. */
    private val closed = CompletableDeferred<Unit>()

    /** Turns failures of the runtime into problems. */
    private val locator = FailureLocator(prepared.plan, client.endpoint)

    /** The bounds of stopping and disconnecting. */
    private val bounds = CleanupBounds.DEFAULT

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

    /** The events sent without an observed reaction since the last start or forced state. */
    private val unanswered = UnansweredEvents()

    /** Starts taking messages and deploying, in the session's scope. */
    fun start() {
        scope.launch(CoroutineName("session-conductor")) {
            for (message in inbox.messages) take(message)
        }
        scope.launch(CoroutineName("session-deployment")) { deploy() }
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

    /** Runs the deployment and hands its end to the inbox. */
    private suspend fun deploy() {
        val request = DeployRequest(prepared.plan, client, clientId, deployOptions, scope, SessionDeployObservation(router, prepared))
        val result = try {
            deployer.deploy(request, Listener())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (broken: Exception) {
            logger.error("The deployment of {} broke", prepared.plan.name, broken)
            inbox.offer(ConductorMessage.DeployBroke(broken))
            return
        }
        if (departure.deployEnded(result) && result is DeploymentResult.Deployed) {
            withContext(NonCancellable) { abandon(result) }
        }
        inbox.offer(ConductorMessage.DeployEnded(result))
    }

    /**
     * Takes care of an environment the deployment finished after the session was closed: stops it, as the cleanup of
     * a cancelled deployment would, disconnects and records it.
     */
    private suspend fun abandon(deployed: DeploymentResult.Deployed) {
        val environment = deployed.environment
        withTimeoutOrNull(bounds.stop) { environment.stop() }
        withTimeoutOrNull(bounds.disconnect) { deployed.connection.disconnect() }
        deployed.connection.close()
        departure.leave(environment.key, environment.phase.value, "Stopped because the session was closed while it deployed.")
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
                is ConductorMessage.PhaseChanged -> if (!deploying()) settle(environment().phase.value)
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

    /** Carries out the command of [message] if the state allows it. */
    private suspend fun command(message: ConductorMessage.Command) {
        val state = status.state.value
        CommandRules.refusal(message.command, state)?.let { reason ->
            message.reply.complete(SessionCommandOutcome.Refused(state, reason))
            return
        }
        val environment = environment()
        val outcome = when (val command = message.command) {
            SessionCommand.Start -> outcomeOf(environment.start()) { unanswered.reset() }
            SessionCommand.Stop -> {
                beginStop(StopWaiter.Command(message.reply), failure = null)
                return
            }
            is SessionCommand.ForceState -> {
                noteUnanswered()
                outcomeOf(environment.forceState(command.attachedTo, command.state)) { unanswered.reset() }
            }
            is SessionCommand.SetObservation -> if (command.on) outcomeOf(router.switchOn()) else outcomeOf(router.switchOff())
            SessionCommand.Pull -> outcomeOf(router.pull())
        }
        message.reply.complete(outcome)
    }

    /**
     * The outcome of a command whose call ended with [call]: [done] runs on success and the state is settled from the
     * phase at once; a rejection by the runtime is a refusal with the runtime's text; every failure is journaled.
     */
    private fun outcomeOf(call: CallOutcome<*>, done: () -> Unit = {}): SessionCommandOutcome {
        val outcome = when (call) {
            is CallOutcome.Done -> {
                done()
                SessionCommandOutcome.Done
            }
            is CallOutcome.Refused -> SessionCommandOutcome.Refused(status.state.value, call.refusal.reason)
            is CallOutcome.Failed -> failed(call.failure)
        }
        created.get()?.let { settle(it.phase.value) }
        return outcome
    }

    /** The outcome of a command whose call failed with [failure], journaled. */
    private fun failed(failure: RuntimeFailure): SessionCommandOutcome {
        val problem = locator.unlocated(failure)
        record(SessionActivity.CallFailed(problem, clock.elapsed()))
        return if (failure is RuntimeFailure.Rejected && failure.kind != RejectionKind.UNKNOWN_ENVIRONMENT) {
            SessionCommandOutcome.Refused(status.state.value, failure.message)
        } else {
            SessionCommandOutcome.Failed(problem)
        }
    }

    /** Sends [frame] over the connection if the state allows injecting events. */
    private suspend fun send(frame: EventFrame): SendOutcome {
        CommandRules.injectionRefusal(status.state.value)?.let { return notAllowed(it) }
        val connection = connection ?: return SendOutcome.NotConnected(ConnectionState.Idle)
        val outcome = connection.send(frame)
        if (outcome is SendOutcome.Written) {
            record(SessionActivity.EventSent(outcome.frameText, clock.elapsed()))
            unanswered.sent(stacks())
        }
        return outcome
    }

    /** The refusal of an event, for [reason], in the environment's phase. */
    private fun notAllowed(reason: String): SendOutcome =
        SendOutcome.NotAllowed(
            LifecycleRefusal(LifecycleOperation.INJECT_EVENT, created.get()?.phase?.value ?: EnvironmentPhase.Configuring(Uploads.NONE), reason),
        )

    /** Notes in the journal that events may be discarded by a forced state, if some got no observed reaction. */
    private fun noteUnanswered() {
        val count = unanswered.count(stacks())
        if (count == 0) return
        val events = if (count == 1) "1 event was" else "$count events were"
        record(
            SessionActivity.Note(
                "$events sent since the last start or forced state without any machine changing its state; forcing a " +
                    "state discards the events still queued, so they may have been discarded.",
                clock.elapsed(),
            ),
        )
    }

    /** The active stack of every machine as the observed model shows it now. */
    private fun stacks(): Map<String, List<String>> = router.model.value.states.machines.mapValues { it.value.stack }

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
     * Starts stopping the environment on its own coroutine, with [waiter] waiting for the end; [failure] is the problem
     * the session failed with, if it stops because of a failure and keeps its failed state.
     */
    private fun beginStop(waiter: StopWaiter, failure: DeployProblem?) {
        val environment = environment()
        stop = StopInProgress(status.state.value, failure, mutableListOf(waiter))
        if (failure == null) publish(SessionState.Stopping(environment.key))
        departure.stopStarts()
        val connection = connection
        scope.launch(CoroutineName("session-stop")) {
            var entry: LedgerEntry? = null
            val outcome = withContext(NonCancellable) {
                val stopped = withTimeoutOrNull(bounds.stop) { environment.stop() }
                val closedMeanwhile = departure.stopEnded()
                entry = when {
                    stopped is CallOutcome.Done -> departure.record(environment.key, LedgerDisposition.STOPPED, "Stopped by the session.")
                    closedMeanwhile -> departure.leave(environment.key, environment.phase.value, "Left on the runtime when the session was closed during a stop that failed.")
                    else -> null
                }
                stopped
            }
            if (outcome is CallOutcome.Done) {
                connection?.let { letEnd(it) }
                router.environmentStopped()
            }
            inbox.offer(ConductorMessage.StopFinished(outcome, entry))
        }
    }

    /**
     * Lets [connection] end by itself after a stop, as the runtime closes it and the environment's phase ends it, then
     * disconnects it, which does nothing if it ended already; both bounded.
     */
    private suspend fun letEnd(connection: RuntimeConnection) {
        withTimeoutOrNull(bounds.disconnect) { connection.state.first { it is ConnectionState.Ended } }
        withTimeoutOrNull(bounds.disconnect) { connection.disconnect() }
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
            is CallOutcome.Failed -> {
                val failed = failed(outcome.failure)
                restore(running)
                failed
            }
            null -> {
                val problem = DeployProblem(
                    Severity.ERROR,
                    DeployProblemCodes.RUNTIME_NO_ANSWER,
                    "Stopping $key took longer than ${bounds.stop}; the environment may still run.",
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
        created.get()?.let { settle(it.phase.value) }
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
            is DeploymentResult.Deployed -> {
                connection = result.connection
                result.findings.forEach(::raise)
                val environment = result.environment
                created.set(environment)
                when (val verdict = PhaseStates.of(environment.phase.value, environment.key)) {
                    is PhaseVerdict.Keeps -> {
                        publish(if (deployOptions.start) SessionState.Running(environment.key) else SessionState.Ready(environment.key))
                        settledPhase = environment.phase.value
                        verdict.problem?.let(::raise)
                    }
                    else -> settle(environment.phase.value)
                }
                endWhileDeploying?.let { end ->
                    endWhileDeploying = null
                    connectionEnded(end)
                }
            }
            is DeploymentResult.Failed -> {
                raise(result.failure.problem)
                result.cleanup.leftBehind?.leftBehind()?.let(::raise)
                publish(SessionState.Failed(result.failure.problem, result.cleanup.leftBehind))
            }
        }
        shutdownsAfterDeployment.toList().also { shutdownsAfterDeployment.clear() }.forEach(::shutdown)
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
        val key = environment().key
        when (val verdict = PhaseStates.of(phase, key)) {
            is PhaseVerdict.Becomes -> {
                if (verdict.state != state) {
                    publish(verdict.state)
                    verdict.problem?.let(::raise)
                }
            }
            is PhaseVerdict.Keeps -> verdict.problem?.let(::raise)
            is PhaseVerdict.Loses -> lose(verdict.problem)
        }
    }

    /** Takes the end of the connection. */
    private suspend fun connectionEnded(end: ConnectionEnd) {
        val state = status.state.value
        when {
            state == SessionState.Deploying -> endWhileDeploying = end
            stop != null || finalized || state is SessionState.Failed || state == SessionState.Closed -> Unit
            else -> when (end) {
                is ConnectionEnd.HandshakeRejected -> {
                    val problem = DeployProblem(
                        Severity.ERROR,
                        DeployProblemCodes.HANDSHAKE_REJECTED,
                        "The runtime rejected the WebSocket handshake of the session, so it receives nothing more; the " +
                            "environment is stopped.",
                        null,
                    )
                    raise(problem)
                    publish(SessionState.Failed(problem, null))
                    beginStop(StopWaiter.None, failure = problem)
                }
                ConnectionEnd.EnvironmentStopped -> finalizeStop()
                is ConnectionEnd.EnvironmentLost -> lose(PhaseStates.lost(environment().key, end.reason))
                is ConnectionEnd.GaveUp -> raise(
                    DeployProblem(
                        Severity.WARNING,
                        DeployProblemCodes.CONNECTION_LOST,
                        "The connection gave up reconnecting after ${end.attempts} attempts (${end.lastFailure}); the model " +
                            "is no longer observed and events cannot be injected.",
                        null,
                    ),
                )
                ConnectionEnd.Disconnected -> Unit
            }
        }
    }

    /** Settles a stop the session did not make: reads the final model, disconnects and records the stopped environment. */
    private suspend fun finalizeStop() {
        finalized = true
        val environment = environment()
        router.environmentStopped()
        connection?.let { letEnd(it) }
        departure.record(environment.key, LedgerDisposition.STOPPED, "Stopped by somebody else than the session.")
        settledPhase = EnvironmentPhase.Stopped
        publish(SessionState.Stopped(environment.key))
        record(SessionActivity.Note("The environment ${environment.key} was stopped by somebody else than the session.", clock.elapsed()))
    }

    /** The session lost its environment for good: it fails with [problem] and records it as lost. */
    private fun lose(problem: DeployProblem) {
        if (status.state.value is SessionState.Failed) return
        val environment = environment()
        departure.record(environment.key, LedgerDisposition.LOST, "The runtime no longer knows the environment.")
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

    /** True while the deployment runs. */
    private fun deploying(): Boolean = status.state.value == SessionState.Deploying

    /** The environment, which every command after the deployment has. */
    private fun environment(): RuntimeEnvironment = checkNotNull(created.get()) { "the environment exists once the deployment ended" }

    /**
     * The deployment's listener: hands the progress and the created environment to the inbox and the connection, before
     * it is opened, to the router. Confined to the deploying coroutine.
     */
    private inner class Listener : DeployListener {
        /** The created environment, which the connection belongs to. */
        private var environment: RuntimeEnvironment? = null

        override fun progress(progress: DeployProgress) {
            inbox.offer(ConductorMessage.Progressed(progress))
        }

        override fun environmentCreated(environment: RuntimeEnvironment) {
            this.environment = environment
            inbox.offer(ConductorMessage.EnvironmentCreated(environment))
        }

        override fun connected(connection: RuntimeConnection) {
            router.attach(checkNotNull(environment) { "the connection belongs to the created environment" }, connection)
        }
    }

    private companion object {
        /** The conductor's logger. */
        private val logger = LoggerFactory.getLogger(SessionConductor::class.java)

        /** The answer to a command of a closed session. */
        private val CLOSED = SessionCommandOutcome.Refused(SessionState.Closed, "The session is closed.")
    }
}

/**
 * A stop the conductor runs.
 *
 * @property before the state before the stop, restored if it does not stop the environment.
 * @property failure the problem the session failed with, if it stops because of it, or null.
 * @property waiters who waits for the stop's end.
 */
internal class StopInProgress(val before: SessionState, val failure: DeployProblem?, val waiters: MutableList<StopWaiter>)

/** Who waits for the end of a stop. */
internal sealed interface StopWaiter {
    /** Hears that the stop ended with [outcome]. */
    fun answer(outcome: SessionCommandOutcome)

    /**
     * The caller of the stop command, who hears the outcome.
     *
     * @property reply where it goes.
     */
    data class Command(val reply: CompletableDeferred<SessionCommandOutcome>) : StopWaiter {
        override fun answer(outcome: SessionCommandOutcome) {
            reply.complete(outcome)
        }
    }

    /**
     * A shutdown, which goes on whatever the outcome.
     *
     * @property reply where the end goes.
     */
    data class Shutdown(val reply: CompletableDeferred<Unit>) : StopWaiter {
        override fun answer(outcome: SessionCommandOutcome) {
            reply.complete(Unit)
        }
    }

    /** Nobody: the session stops because it failed. */
    data object None : StopWaiter {
        override fun answer(outcome: SessionCommandOutcome) = Unit
    }
}

/**
 * The events sent since the last start or forced state that got no observed reaction: as long as no machine changes its
 * active stack, every sent event counts; a change starts the count again with the next event. Confined to the conductor.
 */
internal class UnansweredEvents {
    /** The active stacks when the first counted event was sent, or null if none is counted. */
    private var since: Map<String, List<String>>? = null

    /** The counted events. */
    private var sent: Int = 0

    /** Counts an event sent while the machines had [stacks]. */
    fun sent(stacks: Map<String, List<String>>) {
        if (since == stacks) {
            sent++
        } else {
            since = stacks
            sent = 1
        }
    }

    /** The counted events if the machines still have [stacks], else 0: a change of a stack is a reaction. */
    fun count(stacks: Map<String, List<String>>): Int = if (since == stacks) sent else 0

    /** Forgets the counted events, after a start or a forced state. */
    fun reset() {
        since = null
        sent = 0
    }
}
