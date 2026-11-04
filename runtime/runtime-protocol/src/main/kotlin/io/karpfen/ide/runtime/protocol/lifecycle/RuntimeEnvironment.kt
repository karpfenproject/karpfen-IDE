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
package io.karpfen.ide.runtime.protocol.lifecycle

import io.karpfen.ide.runtime.protocol.catalogue.AccessKey
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * The handle of one environment on the runtime: every call that concerns the environment goes through it, and it keeps
 * the environment's lifecycle phase on the client's side. Handles have no public constructor; they come from the
 * client's `createEnvironment()` in the phase `Configuring` with nothing uploaded, so nobody reaches the unguarded routes
 * and repeats a lifecycle call.
 *
 * Before every call the handle checks the operation against the phase (the verdict that [allows] answers) and refuses
 * what the runtime would mishandle, sending nothing: configuring calls only while configuring, the metamodel before the
 * event definitions and the model, activation once both are there, a start only of an activated environment, no
 * restart of a stopped one (a stopped environment is final; running again deploys a fresh one). After every call it
 * updates the phase from the outcome; a rejection that shows the runtime's phase differs from the believed one, and an
 * outcome that leaves open whether the call was applied, are settled by reading a snapshot, whose phase the runtime
 * reports. What cannot be settled becomes [EnvironmentPhase.Uncertain], a key the runtime no longer knows
 * [EnvironmentPhase.Lost]. A stopped environment stays stopped whatever a later snapshot reports, unless it is lost.
 *
 * Threading (MUST): every function may be called from any coroutine. The handle serializes its calls, so two calls
 * never overlap on the wire and the phase changes in call order; [phase] has the handle as its one writer.
 *
 * Contract (MUST):
 * - A refused operation answers [CallOutcome.Refused] and sends nothing; a failed call answers [CallOutcome.Failed]; no
 *   function throws for a runtime or transport failure.
 * - [activate], [start] and [stop] complete even when the caller is cancelled: the outcome is recorded in [phase],
 *   then the cancellation is rethrown.
 * - A `CancellationException` of the caller is always rethrown.
 */
public interface RuntimeEnvironment {
    /** The environment's key on the runtime. */
    public val key: EnvironmentKey

    /** The environment's lifecycle phase as the client keeps it. */
    public val phase: StateFlow<EnvironmentPhase>

    /**
     * The refusal the next call of [operation] would get in the current phase, or null if it would be sent, so that a
     * user interface can enable its commands with it.
     */
    public fun allows(operation: LifecycleOperation): LifecycleRefusal?

    /**
     * Uploads the domain metamodel [text]; allowed while configuring, before the event definitions and the model, which
     * the runtime reads against the metamodel's types.
     */
    public suspend fun setMetamodel(text: String): CallOutcome<Unit>

    /** Uploads the event definitions [text]; allowed while configuring, after the metamodel. */
    public suspend fun setEventDefinitions(text: String): CallOutcome<Unit>

    /** Uploads the model [text]; allowed while configuring, after the metamodel. */
    public suspend fun setModel(text: String): CallOutcome<Unit>

    /**
     * Attaches the state machine [text] to the object with the key [attachedTo]; allowed while configuring. Machines run
     * in the order they were attached.
     */
    public suspend fun setStateMachine(attachedTo: String, text: String): CallOutcome<Unit>

    /** Sets the tick delay to [milliseconds]; allowed while configuring. */
    public suspend fun setTickDelay(milliseconds: Int): CallOutcome<Unit>

    /** Sets the event time to live to [milliseconds], 0 for events that never expire; allowed while configuring. */
    public suspend fun setEventTtl(milliseconds: Long): CallOutcome<Unit>

    /** Registers [client] as an observer of the object [objectId]; allowed while configuring (frozen at activation). */
    public suspend fun registerObjectObserver(client: ClientId, objectId: String): CallOutcome<Unit>

    /** Registers [client] as a listener of the event [domain]; allowed while configuring (frozen at activation). */
    public suspend fun registerDomainListener(client: ClientId, domain: String): CallOutcome<Unit>

    /**
     * Has a single-use access key issued for a WebSocket connection of [client], with the stack changes [observatory]
     * asks for; allowed while configuring, activated, running or halted (the runtime closes the sockets of a stopped
     * environment).
     */
    public suspend fun issueAccessKey(client: ClientId, observatory: Observatory): CallOutcome<AccessKey>

    /**
     * Activates the environment: the runtime builds its engine over the uploaded texts and freezes the tick delay, the
     * time to live, the observers and the listeners. Allowed once, while configuring with a metamodel and a model.
     * Completes even when the caller is cancelled.
     */
    public suspend fun activate(): CallOutcome<Unit>

    /** Starts the activated environment's engine. Completes even when the caller is cancelled. */
    public suspend fun start(): CallOutcome<Unit>

    /**
     * Stops the environment; the runtime closes its WebSocket sessions with `Environment stopped`. Answered locally for
     * a stopped environment. Completes even when the caller is cancelled.
     */
    public suspend fun stop(): CallOutcome<Unit>

    /**
     * Forces the machine attached to [modelElement] into [state], at the next tick; allowed while activated or running.
     *
     * The runtime's side effects: it discards every queued external event of the whole environment and, at the next
     * tick, the event-bus bucket of the domain named like [modelElement]; and a state forced before the first tick
     * suppresses the `ENTRY` blocks of the initial states.
     */
    public suspend fun forceState(modelElement: String, state: String): CallOutcome<Unit>

    /** Reads the state machine text uploaded for [modelElement], as it was uploaded; allowed unless the environment is lost. */
    public suspend fun stateMachineSource(modelElement: String): CallOutcome<String>

    /**
     * Reads a snapshot of the model, between two ticks of a running environment; its phase settles the handle's phase,
     * except that a stopped environment stays stopped while the runtime still reports its last tick as running.
     */
    public suspend fun snapshot(): CallOutcome<RuntimeModelSnapshot>

    /**
     * Subscribes [client] to every object and stack change of the environment. The runtime answers only once a
     * WebSocket session of [client] is attached, which makes the subscription the point from which every later push
     * reaches the client. Allowed while configuring, activated, running or halted.
     */
    public suspend fun subscribeModel(client: ClientId): CallOutcome<Unit>

    /** Ends the model subscription of [client]; its other subscriptions stay. Allowed unless the environment is lost. */
    public suspend fun unsubscribeModel(client: ClientId): CallOutcome<Unit>
}
