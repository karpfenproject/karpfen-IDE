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
import io.karpfen.ide.runtime.protocol.catalogue.FeatureName
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * The handle of one environment on the runtime. Every call that concerns the environment goes through it, and it keeps
 * the environment's lifecycle phase on the client's side. Handles have no public constructor. They come from the
 * client's `createEnvironment()` in the phase `Configuring` with nothing uploaded. So nobody reaches the unguarded
 * routes and repeats a lifecycle call.
 *
 * Before every call the handle checks the operation against the phase. That check is the verdict [allows] returns. The
 * handle refuses what the runtime would mishandle and then sends nothing. Configuring calls are allowed only while
 * configuring. The metamodel comes before the event definitions and the model. Activation needs both the metamodel and
 * the model. A start needs an activated environment. A stopped environment is never restarted, because a stopped
 * environment is final. Running the model again deploys a fresh environment.
 *
 * After every call the handle updates the phase from the outcome. Two cases are settled by reading a snapshot, whose
 * phase the runtime reports. The first is a rejection that shows the runtime's phase differs from the one the handle
 * believes. The second is an outcome that leaves open whether the call was applied. What cannot be settled becomes
 * [EnvironmentPhase.Uncertain]. A key the runtime no longer knows becomes [EnvironmentPhase.Lost]. A stopped
 * environment stays stopped whatever a later snapshot reports, unless it is lost.
 *
 * Every function may be called from any coroutine. The handle serializes its calls, so two calls never overlap on the
 * wire and the phase changes in call order. The handle is the only writer of [phase].
 *
 * A refused operation returns [CallOutcome.Refused] and sends nothing. A failed call returns [CallOutcome.Failed]. No
 * function throws for a runtime or transport failure. [activate], [start] and [stop] complete even when the caller is
 * cancelled. They record the outcome in [phase] and then rethrow the cancellation. A `CancellationException` of the
 * caller is always rethrown.
 */
public interface RuntimeEnvironment {
    /** The environment's key on the runtime. */
    public val key: EnvironmentKey

    /** The environment's lifecycle phase as the client keeps it. */
    public val phase: StateFlow<EnvironmentPhase>

    /**
     * Returns the refusal that the next call of [operation] would get in the current phase, or null if the call would
     * be sent. A user interface can enable its commands with it.
     */
    public fun allows(operation: LifecycleOperation): LifecycleRefusal?

    /**
     * Uploads the domain metamodel [text]. It is allowed while configuring, before the event definitions and the
     * model, because the runtime reads those against the metamodel's types.
     */
    public suspend fun setMetamodel(text: String): CallOutcome<Unit>

    /** Uploads the event definitions [text]. It is allowed while configuring, after the metamodel. */
    public suspend fun setEventDefinitions(text: String): CallOutcome<Unit>

    /** Uploads the model [text]. It is allowed while configuring, after the metamodel. */
    public suspend fun setModel(text: String): CallOutcome<Unit>

    /**
     * Attaches the state machine [text] to the object with the key [attachedTo]. It is allowed while configuring.
     * Machines run in the order they were attached.
     */
    public suspend fun setStateMachine(attachedTo: String, text: String): CallOutcome<Unit>

    /** Sets the tick delay to [milliseconds]. It is allowed while configuring. */
    public suspend fun setTickDelay(milliseconds: Int): CallOutcome<Unit>

    /** Sets the event time to live to [milliseconds]. 0 means events never expire. It is allowed while configuring. */
    public suspend fun setEventTtl(milliseconds: Long): CallOutcome<Unit>

    /**
     * Registers [client] as an observer of the object [objectId]. It is allowed while configuring, because activation
     * freezes the observers.
     */
    public suspend fun registerObjectObserver(client: ClientId, objectId: String): CallOutcome<Unit>

    /**
     * Registers [client] as a listener of the event [domain]. It is allowed while configuring, because activation
     * freezes the listeners.
     */
    public suspend fun registerDomainListener(client: ClientId, domain: String): CallOutcome<Unit>

    /**
     * Has a single-use access key issued for a WebSocket connection of [client], with the stack changes [observatory]
     * asks for. It is allowed while configuring, activated, running or halted. It is refused for a stopped environment,
     * because the runtime closes the sockets of a stopped environment.
     */
    public suspend fun issueAccessKey(client: ClientId, observatory: Observatory): CallOutcome<AccessKey>

    /**
     * Activates the environment. The runtime builds its engine over the uploaded texts and freezes the tick delay, the
     * time to live, the observers and the listeners. It is allowed once, while configuring with a metamodel and a
     * model. It completes even when the caller is cancelled.
     */
    public suspend fun activate(): CallOutcome<Unit>

    /** Starts the activated environment's engine. It completes even when the caller is cancelled. */
    public suspend fun start(): CallOutcome<Unit>

    /**
     * Stops the environment. The runtime closes its WebSocket sessions with `Environment stopped`. For an environment
     * that is already stopped, the handle answers locally. It completes even when the caller is cancelled.
     */
    public suspend fun stop(): CallOutcome<Unit>

    /**
     * Forces the machine attached to [modelElement] into [state] at the next tick. It is allowed while activated or
     * running.
     *
     * The runtime has side effects here. It discards every queued external event of the whole environment. At the next
     * tick it also discards the event-bus bucket of the domain named like [modelElement]. A state forced before the
     * first tick suppresses the `ENTRY` blocks of the initial states.
     */
    public suspend fun forceState(modelElement: String, state: String): CallOutcome<Unit>

    /**
     * Reads the state machine text uploaded for [modelElement], as it was uploaded. It is allowed unless the
     * environment is lost.
     */
    public suspend fun stateMachineSource(modelElement: String): CallOutcome<String>

    /**
     * Reads a snapshot of the model, taken between two ticks of a running environment. The snapshot's phase settles the
     * handle's phase. The exception is a stopped environment, which stays stopped while the runtime still reports its
     * last tick as running.
     */
    public suspend fun snapshot(): CallOutcome<RuntimeModelSnapshot>

    /**
     * Subscribes [client] to every object and stack change of the environment. The runtime answers only once a
     * WebSocket session of [client] is attached, which makes the subscription the point from which every later push
     * reaches the client. Allowed while configuring, activated, running or halted.
     */
    public suspend fun subscribeModel(client: ClientId): CallOutcome<Unit>

    /**
     * Ends the model subscription of [client]. Its other subscriptions stay. It is allowed unless the environment is
     * lost.
     */
    public suspend fun unsubscribeModel(client: ClientId): CallOutcome<Unit>

    /**
     * Switches [feature] on, together with the features it needs. It is allowed unless the environment is lost, because
     * the runtime switches features in every phase of an existing environment. The runtime refuses a feature that is on
     * already or that it does not know. A feature changes how the engine runs, never the phase.
     */
    public suspend fun activateFeature(feature: FeatureName): CallOutcome<Unit>

    /**
     * Switches [feature] off, together with the features that need it. It is allowed unless the environment is lost.
     * The runtime refuses a feature that is not on or that it does not know.
     */
    public suspend fun deactivateFeature(feature: FeatureName): CallOutcome<Unit>

    /** Reads the names of the features that are on. It is allowed unless the environment is lost. */
    public suspend fun activeFeatures(): CallOutcome<Set<FeatureName>>

    /**
     * Sends [message] to [feature] and returns the feature's answer. It is allowed unless the environment is lost. The
     * runtime refuses a message to a feature that is not on, and a feature refuses a message it does not understand.
     */
    public suspend fun messageFeature(feature: FeatureName, message: String): CallOutcome<String>
}
