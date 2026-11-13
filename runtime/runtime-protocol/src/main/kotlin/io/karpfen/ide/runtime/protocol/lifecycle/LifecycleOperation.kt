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

import io.karpfen.ide.runtime.protocol.api.RuntimeFailure

/**
 * What a client can do with an environment. The handle checks each operation against the environment's phase before it
 * calls the runtime.
 */
public enum class LifecycleOperation {
    /** Upload the domain metamodel. */
    SET_METAMODEL,

    /** Upload the event definitions. */
    SET_EVENT_DEFINITIONS,

    /** Upload the model. */
    SET_MODEL,

    /** Attach a state machine to an object key. */
    SET_STATE_MACHINE,

    /** Set the tick delay. */
    SET_TICK_DELAY,

    /** Set the event time to live. */
    SET_EVENT_TTL,

    /** Register an object observer. */
    REGISTER_OBJECT_OBSERVER,

    /** Register a domain listener. */
    REGISTER_DOMAIN_LISTENER,

    /** Have an access key issued for a WebSocket connection. */
    ISSUE_ACCESS_KEY,

    /** Activate the environment. */
    ACTIVATE,

    /** Start the environment. */
    START,

    /** Stop the environment. */
    STOP,

    /** Force the active state of a machine. */
    FORCE_STATE,

    /** Read the uploaded text of a state machine. */
    READ_STATE_MACHINE_SOURCE,

    /** Read a snapshot of the model. */
    READ_SNAPSHOT,

    /** Subscribe to every object and stack change. */
    SUBSCRIBE_MODEL,

    /** End the model subscription. */
    UNSUBSCRIBE_MODEL,

    /** Send an event over the connection. */
    INJECT_EVENT,

    /** Switch a feature on. */
    ACTIVATE_FEATURE,

    /** Switch a feature off. */
    DEACTIVATE_FEATURE,

    /** Read which features are on. */
    READ_ACTIVE_FEATURES,

    /** Send a message to a feature. */
    MESSAGE_FEATURE,
}

/**
 * Why the handle refused an operation without calling the runtime.
 *
 * @property operation the refused operation.
 * @property phase the phase in which it was refused.
 * @property reason why, in words.
 */
public data class LifecycleRefusal(
    public val operation: LifecycleOperation,
    public val phase: EnvironmentPhase,
    public val reason: String,
)

/** The outcome of an operation on an environment handle. */
public sealed interface CallOutcome<out T> {
    /**
     * The runtime did it.
     *
     * @property value what it answered, read.
     */
    public data class Done<out T>(public val value: T) : CallOutcome<T>

    /**
     * The handle refused the operation in the environment's phase, and nothing was sent.
     *
     * @property refusal why.
     */
    public data class Refused(public val refusal: LifecycleRefusal) : CallOutcome<Nothing>

    /**
     * The call failed.
     *
     * @property failure why, and how far the call got.
     */
    public data class Failed(public val failure: RuntimeFailure) : CallOutcome<Nothing>
}

/** Which stack changes an access key's observatory registration asks for. */
public sealed interface Observatory {
    /** Every state machine of the environment (`*`). */
    public data object EveryMachine : Observatory

    /**
     * The state machine attached to [modelElement].
     *
     * @property modelElement its `attachedTo` key.
     */
    public data class Machine(public val modelElement: String) : Observatory

    /** No machine. The key is issued without an observatory registration, through `/registerClientForWebSocket`. */
    public data object NoMachine : Observatory
}
