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
package io.karpfen.ide.runtime.protocol.message

/**
 * An event that was published on an environment's event bus, as a `domainEvent` push announces it.
 *
 * @property name the event's name.
 * @property payload the raw payload text, never parsed as JSON by the runtime's announcement.
 * @property payloadFormat `none`, `json` or `kmodel`.
 * @property source where the event came from. `INTERNAL` means an action of the same engine raised it.
 *   `EXTERNAL_ENGINE` means a machine in another engine thread raised it. `EXTERNAL_MESSAGE` means a client injected
 *   it, for example over the WebSocket.
 * @property timestamp the receipt or raise time in epoch milliseconds.
 * @property ttlMs the effective time to live in milliseconds.
 */
public data class PublishedEvent(
    public val name: String,
    public val payload: String,
    public val payloadFormat: String,
    public val source: String,
    public val timestamp: Long,
    public val ttlMs: Long,
)

/**
 * A message the runtime pushed to the client, decoded from its envelope. Object, stack and state machine pushes carry
 * the revision of their observation from one counter of the environment. A model subscriber receives every revision of
 * its environment in order. So a revision above the last one plus one proves that pushes were lost. Domain events and
 * engine statuses carry no revision.
 */
public sealed interface RuntimePush {
    /**
     * An object changed (`objectChanged`).
     *
     * @property objectId the id of the changed object.
     * @property revision the revision of the observation.
     * @property value the object's whole JSON after the change, nested objects included.
     */
    public data class ObjectChanged(
        public val objectId: String,
        public val revision: Long,
        public val value: WireValue.WireObject,
    ) : RuntimePush

    /**
     * An object was removed from the model (`objectDeleted`).
     *
     * @property objectId the id of the removed object.
     * @property revision the revision of the observation.
     */
    public data class ObjectDeleted(public val objectId: String, public val revision: Long) : RuntimePush

    /**
     * The active stack of a state machine changed (`stateChange`).
     *
     * @property modelElementId the key of the object whose state machine instance changed its stack.
     * @property stack the plain state names of every parallel branch, outermost first, branches concatenated.
     * @property revision the revision of the observation.
     */
    public data class StateChanged(
        public val modelElementId: String,
        public val stack: List<String>,
        public val revision: Long,
    ) : RuntimePush

    /**
     * A state machine instance appeared on an object (`stateMachineAttached`). The runtime sends one for every instance
     * at each start of the environment, before the first tick, and one for every instance a tick attached, at the end
     * of that tick. The instance does its first step in the next tick, so its first `stateChange` follows later.
     *
     * @property modelElementId the key of the object, which identifies the instance, since an object has at most one.
     * @property stateMachine the name of the attachable state machine the instance runs, or null for a state machine
     *   attached to the object by the run configuration.
     * @property revision the revision of the observation.
     */
    public data class StateMachineAttached(
        public val modelElementId: String,
        public val stateMachine: String?,
        public val revision: Long,
    ) : RuntimePush

    /**
     * A state machine instance left its object (`stateMachineDetached`), at the end of the tick in which its object was
     * removed or `EXCHANGE` ended it. The instance does no further step, and the runtime forgets its stack. An exchange
     * sends this push for the old instance and then a [StateMachineAttached] for the new one under the same key.
     *
     * @property modelElementId the key of the object.
     * @property stateMachine the name of the attachable state machine the instance ran, or null for an attached one.
     * @property revision the revision of the observation.
     */
    public data class StateMachineDetached(
        public val modelElementId: String,
        public val stateMachine: String?,
        public val revision: Long,
    ) : RuntimePush

    /**
     * An event was published on a domain the client listens to (`domainEvent`).
     *
     * @property domain the event's domain.
     * @property event the event, or null if the inner payload lacked a member or had one of the wrong type.
     * @property rawPayload the inner payload, written again compactly.
     */
    public data class DomainEvent(
        public val domain: String,
        public val event: PublishedEvent?,
        public val rawPayload: String,
    ) : RuntimePush

    /**
     * The engine thread started to wait or went on (`engineStatus`). The runtime sends it to every client with a
     * connected WebSocket of the environment, whatever it subscribed for, but only while its feature `TickByTick` is
     * on. It sends one when the thread starts to wait, between two ticks or at a breakpoint, and one when it goes on.
     * A pause that was asked for while the engine still finishes its tick is told only once the thread waits.
     *
     * @property status the engine's status, the same object that the feature `Breakpoint` answers to `getStatus`.
     */
    public data class EngineStatusChanged(public val status: EngineStatus) : RuntimePush

    /**
     * A message of a type the client does not know. It is not a violation, because the runtime may add types.
     *
     * @property messageType the type.
     * @property rawPayload the payload, written again compactly.
     */
    public data class Unrecognized(public val messageType: String, public val rawPayload: String) : RuntimePush
}
