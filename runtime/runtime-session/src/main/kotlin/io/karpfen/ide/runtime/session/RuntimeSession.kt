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

import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.session.deploy.DeployProgress
import io.karpfen.ide.runtime.session.events.EventInjector
import io.karpfen.ide.runtime.session.observation.ModelUpdate
import io.karpfen.ide.runtime.session.observation.ObservedModel
import io.karpfen.ide.runtime.session.problem.DeployProblem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One deployed environment and everything the IDE does with it. That covers the deployment's progress, the commands of
 * its lifecycle, the observed model with its live switch, the domain events, the event injector and a journal. A
 * session deploys a fresh environment when it is launched and never adopts or restarts one. A stopped environment is
 * final.
 *
 * The table lists the commands that each state allows. Debugging stands for the commands of the [debugger]. Any
 * other command answers [SessionCommandOutcome.Refused].
 * Events injected while the session is ready are queued until the start.
 *
 * | State | Allowed |
 * |---|---|
 * | deploying | [shutdown], [close] |
 * | ready | [start], [stop], [forceState], injecting, [setObservation], [pull], [shutdown], [close] |
 * | running | [stop], [forceState], injecting, [setObservation], [pull], debugging, [shutdown], [close] |
 * | halted | [stop], switching observation off, [pull], [shutdown], [close] |
 * | stopping | [close] |
 * | stopped | [pull], [close] |
 * | failed | [close] |
 * | closed | nothing |
 *
 * Every function may be called from any coroutine, including a collector of the session's own flows. The session runs
 * its work on the dispatcher of its options, in a supervisor child of the scope it was launched in. Cancelling that
 * scope closes the session the same way [close] does. One conductor carries out the commands in the order they arrive.
 * It is the single writer of [state], [progress] and [problems]. One router is the only reader of the connection's
 * pushes. It is the single writer of [model], [modelUpdates], [domainEvents] and [activity]. Injected events go through
 * the conductor too, so an injected event never overtakes a stop. So do the commands and questions of the debugger. The
 * session never waits for a collector of its flows.
 *
 * Failures are reported as outcomes and problems, never as exceptions. A `CancellationException` of the caller is
 * rethrown. [close] is idempotent and does not suspend.
 */
public interface RuntimeSession : AutoCloseable {
    /** What the session deploys. */
    public val prepared: PreparedDeployment

    /** The client id the session registers its listeners and its connection as, for its whole life. */
    public val clientId: ClientId

    /** Where the session stands. */
    public val state: StateFlow<SessionState>

    /** The progress of the deployment, step by step. */
    public val progress: StateFlow<DeployProgress>

    /**
     * Every problem of the session so far, oldest first. The list starts with the warnings and infos of the plan,
     * followed by the findings of the deployment and what happened since.
     */
    public val problems: StateFlow<List<DeployProblem>>

    /** The state of the connection to the environment. It is idle until the deployment connects. */
    public val connection: StateFlow<ConnectionState>

    /** The observed model, the seed and the predicted states until something was read from the runtime. */
    public val model: StateFlow<ObservedModel>

    /**
     * Every change of [model], hot and without replay. It never holds the session back. A collector that falls more
     * than [RuntimeSessionOptions.collectorBuffer] updates behind misses the oldest of them, while [model] always shows
     * the latest state.
     */
    public val modelUpdates: Flow<ModelUpdate>

    /**
     * Every event published on a domain the state machines use, hot and without replay. Domain events keep coming
     * while observation is off, because the runtime freezes the listeners when the environment is activated. It never
     * holds the session back. A collector that falls more than [RuntimeSessionOptions.collectorBuffer] events behind
     * misses the oldest of them. The journal keeps them as well.
     */
    public val domainEvents: Flow<ObservedDomainEvent>

    /**
     * The journal. Every new collector gets the latest entries first, as many as the options keep, and then the live
     * ones.
     */
    public val activity: Flow<SessionActivity>

    /** The injectable events of the deployment and the injection of events through the session. */
    public val events: EventInjector

    /**
     * The debugger, which pauses, resumes and steps the engine while the environment runs. A session whose deployment
     * did not switch the runtime's feature `Breakpoint` on has none, which [SessionDebugger.isPresent] tells.
     */
    public val debugger: SessionDebugger

    /** Starts the activated environment. */
    public suspend fun start(): SessionCommandOutcome

    /**
     * Stops the environment for good. The runtime closes the connection, and the session reads the final model and
     * records the environment as stopped in the ledger.
     */
    public suspend fun stop(): SessionCommandOutcome

    /**
     * Forces the state machine instance on the object with the key [attachedTo] into [state] at the next tick, without
     * running any `ENTRY` block. The instance is the one the object has in the latest tick: the attached state machine
     * or the instance of a definition the object got by `behaves after`, `ATTACH` or `EXCHANGE`. [state] is a state of
     * the state machine that instance runs.
     *
     * Forcing has side effects in the runtime. It discards every event still queued in the whole environment, and at
     * the next tick it also discards the events of the domain named like [attachedTo]. A state forced before the first
     * tick suppresses the `ENTRY` blocks of the initial states. When events were sent since the last start or force and
     * no machine changed its state since then, the journal notes that they may have been discarded. When the runtime
     * refuses an unknown machine or state, the refusal comes back as [SessionCommandOutcome.Refused] with its text.
     */
    public suspend fun forceState(attachedTo: String, state: String): SessionCommandOutcome

    /**
     * Switches observation on or off. Switching it on subscribes to the whole model, reads a snapshot first and then
     * applies every push in revision order. Switching it off ends the subscription. After that the model changes only
     * when it is pulled.
     */
    public suspend fun setObservation(on: Boolean): SessionCommandOutcome

    /** Reads a snapshot of the model once and applies it, whether observation is on or off. */
    public suspend fun pull(): SessionCommandOutcome

    /**
     * Ends the session cleanly. It waits for a running deployment to end and stops the environment if it is activated,
     * running or halted. Then it closes the session and waits until all its work has ended, cleanups included.
     */
    public suspend fun shutdown()

    /**
     * Closes the session at once, without suspending. Calling it again does nothing.
     *
     * While the deployment runs, closing cancels it. The deployment's cleanup then stops an environment that was
     * activated already and records it in the ledger. That cleanup cannot be cancelled and is bounded in time.
     * Otherwise closing cancels the session's own work, closes the connection and leaves the environment on the runtime
     * as it is. The ledger records it as left activated, left running, left halted or stopped.
     */
    override fun close()
}
