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
package io.karpfen.ide.runtime.protocol.catalogue

/**
 * The names of the runtime's WebSocket protocol and its JSON, as the runtime writes them. They cover the path and the
 * handshake, the message types, and the members of envelopes, payloads, domain events, snapshots and inbound event
 * frames. They also cover the payload formats, the close codes and reasons, and the prefixes of the keys the runtime
 * issues. The object holds constants only, so every name has exactly one declaration in the client.
 */
public object WireNames {
    /** The path of the WebSocket on the runtime's HTTP host and port. */
    public const val WEB_SOCKET_PATH: String = "/ws"

    /** Separates client id, environment key and access key in the handshake `<clientId>:<envKey>:<accessKey>`. */
    public const val HANDSHAKE_SEPARATOR: String = ":"

    /** The model element of an observatory registration that stands for every state machine of the environment. */
    public const val EVERY_MACHINE: String = "*"

    /** The message type of a changed object. */
    public const val OBJECT_CHANGED: String = "objectChanged"

    /** The message type of an object removed from the model. */
    public const val OBJECT_DELETED: String = "objectDeleted"

    /** The message type of a changed active stack of a state machine. */
    public const val STATE_CHANGE: String = "stateChange"

    /** The message type of an event published on the environment's event bus. */
    public const val DOMAIN_EVENT: String = "domainEvent"

    /**
     * The message type of the engine's status, sent when the engine thread starts to wait and when it goes on, while
     * the feature `TickByTick` is on. Its payload is the breakpoint status with [PAUSED], [WAITING] and [HALT].
     */
    public const val ENGINE_STATUS: String = "engineStatus"

    /**
     * The environment key, a member of the push envelope and of the snapshot. In an inbound event frame, the member of
     * this name holds the event's domain instead.
     */
    public const val ENVIRONMENT_KEY: String = "environmentKey"

    /** The client id, a member of the push envelope. */
    public const val CLIENT_ID: String = "clientId"

    /** The message type, a member of the push envelope. In an inbound event frame it holds the event's name instead. */
    public const val MESSAGE_TYPE: String = "messageType"

    /**
     * The payload. It is a member of the push envelope and of the `domainEvent` payload, where it holds the event's
     * own fields. It is also a member of a published event, where it holds the raw payload text, and of an inbound
     * event frame, where it holds the payload text.
     */
    public const val PAYLOAD: String = "payload"

    /** The id of the changed or deleted object, a member of the `objectChanged` and `objectDeleted` payloads. */
    public const val OBJECT_ID: String = "objectId"

    /** The revision of an observation, a member of the object and stack payloads and of the snapshot. */
    public const val REVISION: String = "revision"

    /** The object's JSON, a member of the `objectChanged` payload. */
    public const val VALUE: String = "value"

    /** The `attachedTo` key of the machine, a member of the `stateChange` payload. */
    public const val MODEL_ELEMENT_ID: String = "modelElementId"

    /** The active stack, a member of the `stateChange` payload. */
    public const val STACK: String = "stack"

    /** The event's domain, a member of the `domainEvent` payload. */
    public const val DOMAIN: String = "domain"

    /** The id of an object in its JSON, the first member of every object. */
    public const val OBJECT_ID_MEMBER: String = "__id__"

    /** The type name of an object in its JSON, the second member of every object. */
    public const val TYPE_MEMBER: String = "__type__"

    /** The event's name, a member of a published event. */
    public const val EVENT: String = "event"

    /** The format of the payload, a member of a published event and of an inbound event frame. */
    public const val PAYLOAD_FORMAT: String = "payloadFormat"

    /** Where the event came from (`INTERNAL`, `EXTERNAL_ENGINE`, `EXTERNAL_MESSAGE`), a member of a published event. */
    public const val SOURCE: String = "source"

    /** The receipt or raise time in epoch milliseconds, a member of a published event. */
    public const val TIMESTAMP: String = "timestamp"

    /** The effective time to live in milliseconds, a member of a published event. */
    public const val TTL_MS: String = "ttlMs"

    /** The lifecycle phase, a member of the snapshot. */
    public const val PHASE: String = "phase"

    /** The active stacks by machine, a member of the snapshot. */
    public const val ACTIVE_STATES: String = "activeStates"

    /** The root objects of the model, a member of the snapshot. */
    public const val ROOTS: String = "roots"

    /** The payload format of an event without a payload. */
    public const val FORMAT_NONE: String = "none"

    /** The payload format of a JSON payload. */
    public const val FORMAT_JSON: String = "json"

    /** The payload format of a KModel payload. */
    public const val FORMAT_KMODEL: String = "kmodel"

    /** The close code of a normal close, also the runtime's close of the sockets of a stopped environment. */
    public const val CLOSE_NORMAL: Int = 1000

    /** The close code of a refused handshake. */
    public const val CLOSE_CANNOT_ACCEPT: Int = 1003

    /** The close code with which the runtime ends a connection that missed a pong. */
    public const val CLOSE_INTERNAL_ERROR: Int = 1011

    /** The close reason of a handshake whose first frame was not text. */
    public const val REASON_FIRST_MESSAGE_NOT_TEXT: String = "First message must be text"

    /** The close reason of a handshake that was not three parts separated by [HANDSHAKE_SEPARATOR]. */
    public const val REASON_INVALID_AUTH_FORMAT: String = "Invalid auth format"

    /** The close reason of a handshake with an unknown, used or foreign access key. */
    public const val REASON_INVALID_CREDENTIALS: String = "Invalid credentials"

    /** The close reason of a connection that missed a pong. */
    public const val REASON_PING_TIMEOUT: String = "Ping timeout"

    /** The close reason of the sockets of an environment that was stopped. */
    public const val REASON_ENVIRONMENT_STOPPED: String = "Environment stopped"

    /** The prefix of every environment key, `env-<epoch millis>`. */
    public const val ENVIRONMENT_KEY_PREFIX: String = "env-"

    /** Separates the feature names in the runtime's answers that list features. */
    public const val FEATURE_SEPARATOR: String = ","

    /** The prefix of every access key, `ak-<clientId>-<epoch millis>-<0..9999>`. */
    public const val ACCESS_KEY_PREFIX: String = "ak-"

    /** The message to the feature `TickByTick` that pauses the engine once the tick it is in has ended. */
    public const val TICK_PAUSE: String = "pause"

    /** The message to the feature `TickByTick` that lets a paused or halted engine run freely again. */
    public const val TICK_RESUME: String = "resume"

    /** The start of the message `tick:N` to the feature `TickByTick`, which lets a waiting engine run `N` ticks. */
    public const val TICK_ADVANCE_PREFIX: String = "tick:"

    /** The start of the answer of the feature `TickByTick` to a `tick:N` it applies, `execution is advancing N ticks`. */
    public const val TICKS_ADVANCING: String = "execution is advancing "

    /** The member of a message to the feature `Breakpoint` that names what the feature is asked to do. */
    public const val BREAKPOINT_ACTION: String = "action"

    /** The action that asks the feature `Breakpoint` what the engine is doing. */
    public const val BREAKPOINT_GET_STATUS: String = "getStatus"

    /**
     * Whether a pause was asked for or a breakpoint halted the engine, a member of the breakpoint status, which is also
     * the payload of the [ENGINE_STATUS] push.
     */
    public const val PAUSED: String = "paused"

    /** Whether the engine thread waits right now, a member of the breakpoint status. */
    public const val WAITING: String = "waiting"

    /** Where the engine waits at a breakpoint, a member of the breakpoint status while it does. */
    public const val HALT: String = "halt"

    /** When the halt happens, `entry`, `exit`, `start` or `end`, a member of a halt. */
    public const val TIMING: String = "timing"

    /** The name of the state the engine halted at, a member of a halt at a state. */
    public const val HALT_STATE: String = "state"

    /** The kind of the transition the engine halted at, `TRANSITION`, `SPLIT` or `JOIN`, a member of a halt. */
    public const val HALT_KIND: String = "kind"

    /** The names of the source states of the transition the engine halted at, a member of a halt. */
    public const val HALT_FROM: String = "from"

    /** The names of the target states of the transition the engine halted at, a member of a halt. */
    public const val HALT_TO: String = "to"

    /** The place of the transition the engine halted at among the transitions of its file, a member of a halt. */
    public const val HALT_INDEX: String = "index"
}
