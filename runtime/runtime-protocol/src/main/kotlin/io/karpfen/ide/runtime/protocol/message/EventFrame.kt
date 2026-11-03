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
 * An event the client injects into the environment of its connection, as one text frame after the handshake. The
 * runtime takes the environment from the handshake and publishes the event on the bus of [domain].
 *
 * @property domain the event's domain, sent in the frame's `environmentKey` member.
 * @property name the event's name, sent in the frame's `messageType` member; for a JSON payload also the name of the
 *   payload's type in the event metamodel.
 * @property payload the payload and its format.
 */
public data class EventFrame(public val domain: String, public val name: String, public val payload: EventPayload)

/** The payload of an [EventFrame], always sent as a JSON string with an explicit format. */
public sealed interface EventPayload {
    /** No payload: an empty text with the format `none`. */
    public data object None : EventPayload

    /**
     * A JSON object, converted by the runtime against the event metamodel.
     *
     * @property text the JSON text.
     */
    public data class Json(public val text: String) : EventPayload

    /**
     * A KModel snippet, read by the runtime against the event metamodel; it takes the first root object.
     *
     * @property text the KModel text.
     */
    public data class Kmodel(public val text: String) : EventPayload
}
