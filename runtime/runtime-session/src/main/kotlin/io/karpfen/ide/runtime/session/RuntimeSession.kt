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
 * One deployed environment and everything the IDE does with it: the deployment's progress, the commands of its
 * lifecycle, the observed model with its live switch, the domain events, the event injector and a journal. A session
 * deploys a fresh environment when it is launched and never adopts or restarts one: a stopped environment is final.
 *
 * Commands per state (any other command answers [SessionCommandOutcome.Refused]):
 *
 * | State | Allowed |
 * |---|---|
 * | deploying | [shutdown], [close] |
 * | ready | [start], [stop], [forceState], injecting (queued until the start), [setObservation], [pull], [shutdown], [close] |
 * | running | [stop], [forceState], injecting, [setObservation], [pull], [shutdown], [close] |
 * | halted | [stop], switching observation off, [pull], [shutdown], [close] |
 * | stopping | [close] |
 * | stopped | [pull], [close] |
 * | failed | [close] |
 * | closed | nothing |
 *
 * Threading (MUST): every function may be called from any coroutine; the session runs its work on the dispatcher of
 * its options, in a supervisor child of the scope it was launched in, and cancelling that scope closes the session as
 * [close] does. One conductor carries out the commands in the order they arrive and is the single writer of [state],
 * [progress] and [problems]; one router is the only reader of the connection's pushes and the single writer of [model],
 * [modelUpdates], [domainEvents] and [activity]. Events go through the conductor too, so an injected event never
 * overtakes a stop.
 *
 * Contract (MUST):
 * - Failures are outcomes and problems, never exceptions; a `CancellationException` of the caller is rethrown.
 * - [close] is idempotent and does not suspend.
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
     * Every problem of the session so far, oldest first: the warnings and infos of the plan, the findings of the
     * deployment and what happened since.
     */
    public val problems: StateFlow<List<DeployProblem>>

    /** The state of the connection to the environment; idle until the deployment connects. */
    public val connection: StateFlow<ConnectionState>

    /** The observed model, the seed and the predicted states until something was read from the runtime. */
    public val model: StateFlow<ObservedModel>

    /** Every change of [model], hot and without replay. */
    public val modelUpdates: Flow<ModelUpdate>

    /**
     * Every event published on a domain the state machines use, hot and without replay. Domain events keep coming
     * while observation is off, because the runtime freezes the listeners when the environment is activated.
     */
    public val domainEvents: Flow<ObservedDomainEvent>

    /** The journal: the latest entries, as many as the options keep, replayed to every new collector, then live. */
    public val activity: Flow<SessionActivity>

    /** The injectable events of the deployment and the injection of events through the session. */
    public val events: EventInjector

    /** Starts the activated environment. */
    public suspend fun start(): SessionCommandOutcome

    /**
     * Stops the environment, for good: the runtime closes the connection, the session reads the final model and
     * records the environment as stopped in the ledger.
     */
    public suspend fun stop(): SessionCommandOutcome

    /**
     * Forces the machine attached to [attachedTo] into [state] at the next tick, without running any `ENTRY` block.
     *
     * The runtime's side effects: it discards every event still queued in the whole environment and, at the next tick,
     * the events of the domain named like [attachedTo]; and a state forced before the first tick suppresses the
     * `ENTRY` blocks of the initial states. When events were sent since the last start or force and no machine changed
     * its state since, the journal notes that they may have been discarded. The runtime's refusal of an unknown machine
     * or state comes back as [SessionCommandOutcome.Refused] with its text.
     */
    public suspend fun forceState(attachedTo: String, state: String): SessionCommandOutcome

    /**
     * Switches observation on or off. On subscribes to the whole model and reads a snapshot first, then applies every
     * push in revision order; off ends the subscription, after which the model changes only when it is pulled.
     */
    public suspend fun setObservation(on: Boolean): SessionCommandOutcome

    /** Reads a snapshot of the model once and applies it, whether observation is on or off. */
    public suspend fun pull(): SessionCommandOutcome

    /**
     * Ends the session cleanly: waits for a running deployment to end, stops the environment if it is activated,
     * running or halted, then closes the session and waits until its work, cleanups included, has ended.
     */
    public suspend fun shutdown()

    /**
     * Closes the session at once, without suspending; idempotent.
     *
     * While the deployment runs, closing cancels it: the deployment's cleanup stops an environment that was activated
     * already and records it in the ledger, without being cancellable and bounded in time. Otherwise closing cancels the
     * session's own work, closes the connection and leaves the environment on the runtime as it is, recorded in the
     * ledger as left activated, left running, left halted or stopped.
     */
    override fun close()
}
