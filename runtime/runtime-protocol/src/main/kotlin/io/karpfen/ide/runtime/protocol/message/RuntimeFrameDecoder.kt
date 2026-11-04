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

import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.WireNames

/**
 * Decodes the text frames the runtime pushes to one client of one environment.
 *
 * A frame is read whole with [RuntimeJson.read], so a frame that is not the runtime's JSON is an
 * [ViolationKind.UNDECODABLE_FRAME]. Its envelope is read by member name: `environmentKey`, `clientId` and
 * `messageType` must be strings and `payload` must be present ([ViolationKind.MALFORMED_PAYLOAD] otherwise), and the
 * environment and the client must be those of the connection ([ViolationKind.FOREIGN_ENVELOPE]). The payload is read
 * by member name too, so the members of `stateChange` and of a published event, which org.json writes, may come in any
 * order:
 *
 * - `objectChanged`: a string `objectId`, an integral `revision` and an object `value` with a string `__id__` and a
 *   string `__type__`. An `objectId` that differs from the `__id__` is reported as an [ViolationKind.ID_MISMATCH]
 *   alongside the push, which carries the `__id__`: the value is what the runtime wrote, so it is applied under its own
 *   id.
 * - `objectDeleted`: a string `objectId` and an integral `revision`.
 * - `stateChange`: a string `modelElementId`, an array of strings `stack` and an integral `revision`.
 * - `domainEvent`: a string `domain` and an object `payload`, whose members `event`, `payload`, `payloadFormat` and
 *   `source` (strings) and `timestamp` and `ttlMs` (integral numbers) make the [PublishedEvent]; a missing or mistyped
 *   member leaves the event null and adds a [ViolationKind.MALFORMED_PAYLOAD] to the push.
 * - any other type: [RuntimePush.Unrecognized], not a violation, since the runtime may add types.
 *
 * A missing revision is a [ViolationKind.MALFORMED_PAYLOAD]: the client requires the runtime with IDE support, which
 * numbers every observation. Raw payloads are written again compactly with [CompactJson]. Every violation keeps the
 * first 200 characters of the frame and the message type and object id, as far as they could be read, also from a frame
 * that is not JSON.
 *
 * Stateless apart from the connection's environment and client, so one decoder may decode frames from any thread.
 *
 * @param environment the environment of the connection.
 * @param client the client id of the connection.
 */
public class RuntimeFrameDecoder(private val environment: EnvironmentKey, private val client: ClientId) {

    /** Decodes one text frame [text]: a push, possibly with violations that did not prevent it, or a violation. */
    public fun decode(text: String): DecodedFrame =
        when (val reading = RuntimeJson.read(text)) {
            is JsonReading.Malformed -> DecodedFrame.Violation(
                ProtocolViolation(
                    kind = ViolationKind.UNDECODABLE_FRAME,
                    detail = "the frame is not JSON as the runtime writes it: ${reading.reason} at offset ${reading.offset}",
                    excerpt = text.excerpt(),
                    messageType = SALVAGED_MESSAGE_TYPE.find(text)?.groupValues?.get(1),
                    objectId = SALVAGED_OBJECT_ID.find(text)?.groupValues?.get(1),
                ),
            )
            is JsonReading.Read -> FrameReading(text).envelope(reading.value)
        }

    /**
     * The reading of one decodable frame: knows the frame's text for excerpts and the message type and object id once
     * they could be read.
     */
    private inner class FrameReading(private val text: String) {

        /** The message type, once read. */
        private var messageType: String? = null

        /** The object id, once read. */
        private var objectId: String? = null

        /** Reads the envelope of [value] and then its payload. */
        fun envelope(value: WireValue): DecodedFrame {
            if (value !is WireValue.WireObject) return malformed("the frame is not a JSON object")
            messageType = value.stringMember(WireNames.MESSAGE_TYPE)
            val payload = value[WireNames.PAYLOAD]
            objectId = (payload as? WireValue.WireObject)?.stringMember(WireNames.OBJECT_ID)
            val environmentKey = value.stringMember(WireNames.ENVIRONMENT_KEY)
            val clientId = value.stringMember(WireNames.CLIENT_ID)
            val type = messageType
            return when {
                environmentKey == null -> malformed("the envelope lacks the string '${WireNames.ENVIRONMENT_KEY}'")
                clientId == null -> malformed("the envelope lacks the string '${WireNames.CLIENT_ID}'")
                type == null -> malformed("the envelope lacks the string '${WireNames.MESSAGE_TYPE}'")
                payload == null -> malformed("the envelope lacks the member '${WireNames.PAYLOAD}'")
                environmentKey != environment.value || clientId != client.value -> violation(
                    ViolationKind.FOREIGN_ENVELOPE,
                    "the push is addressed to client '$clientId' of environment '$environmentKey', not to client " +
                        "'${client.value}' of environment '${environment.value}'",
                )
                else -> payload(type, payload)
            }
        }

        /** Reads [payload] as the payload of a push of [type]. */
        private fun payload(type: String, payload: WireValue): DecodedFrame =
            when (type) {
                WireNames.OBJECT_CHANGED -> objectChanged(payload)
                WireNames.OBJECT_DELETED -> objectDeleted(payload)
                WireNames.STATE_CHANGE -> stateChange(payload)
                WireNames.DOMAIN_EVENT -> domainEvent(payload)
                else -> DecodedFrame.Push(RuntimePush.Unrecognized(type, CompactJson.write(payload)), emptyList())
            }

        /** Reads an `objectChanged` payload. */
        private fun objectChanged(payload: WireValue): DecodedFrame {
            val members = payload as? WireValue.WireObject ?: return malformed("the payload is not an object")
            val id = members.stringMember(WireNames.OBJECT_ID) ?: return missing(WireNames.OBJECT_ID, "a string")
            val revision = members.integralMember(WireNames.REVISION) ?: return missingRevision()
            val value = members.objectMember(WireNames.VALUE) ?: return missing(WireNames.VALUE, "an object")
            val valueId = value.stringMember(WireNames.OBJECT_ID_MEMBER)
            if (valueId == null || value.stringMember(WireNames.TYPE_MEMBER) == null) {
                return malformed(
                    "the value lacks the string '${WireNames.OBJECT_ID_MEMBER}' or the string '${WireNames.TYPE_MEMBER}'",
                )
            }
            val violations = if (valueId == id) {
                emptyList()
            } else {
                listOf(
                    ProtocolViolation(
                        kind = ViolationKind.ID_MISMATCH,
                        detail = "the push names the object '$id' but its value is the object '$valueId', so it is applied " +
                            "to '$valueId'",
                        excerpt = text.excerpt(),
                        messageType = messageType,
                        objectId = id,
                    ),
                )
            }
            return DecodedFrame.Push(RuntimePush.ObjectChanged(valueId, revision, value), violations)
        }

        /** Reads an `objectDeleted` payload. */
        private fun objectDeleted(payload: WireValue): DecodedFrame {
            val members = payload as? WireValue.WireObject ?: return malformed("the payload is not an object")
            val id = members.stringMember(WireNames.OBJECT_ID) ?: return missing(WireNames.OBJECT_ID, "a string")
            val revision = members.integralMember(WireNames.REVISION) ?: return missingRevision()
            return DecodedFrame.Push(RuntimePush.ObjectDeleted(id, revision), emptyList())
        }

        /** Reads a `stateChange` payload, whose members org.json writes in its own order. */
        private fun stateChange(payload: WireValue): DecodedFrame {
            val members = payload as? WireValue.WireObject ?: return malformed("the payload is not an object")
            val element = members.stringMember(WireNames.MODEL_ELEMENT_ID)
                ?: return missing(WireNames.MODEL_ELEMENT_ID, "a string")
            val stack = members.stringsMember(WireNames.STACK) ?: return missing(WireNames.STACK, "an array of strings")
            val revision = members.integralMember(WireNames.REVISION) ?: return missingRevision()
            return DecodedFrame.Push(RuntimePush.StateChanged(element, stack, revision), emptyList())
        }

        /** Reads a `domainEvent` payload; a published event that cannot be read leaves the event null. */
        private fun domainEvent(payload: WireValue): DecodedFrame {
            val members = payload as? WireValue.WireObject ?: return malformed("the payload is not an object")
            val domain = members.stringMember(WireNames.DOMAIN) ?: return missing(WireNames.DOMAIN, "a string")
            val inner = members.objectMember(WireNames.PAYLOAD) ?: return missing(WireNames.PAYLOAD, "an object")
            val event = publishedEvent(inner)
            val violations = if (event != null) {
                emptyList()
            } else {
                listOf(
                    ProtocolViolation(
                        kind = ViolationKind.MALFORMED_PAYLOAD,
                        detail = "the published event of domain '$domain' lacks one of the strings '${WireNames.EVENT}', " +
                            "'${WireNames.PAYLOAD}', '${WireNames.PAYLOAD_FORMAT}' and '${WireNames.SOURCE}' or one of " +
                            "the integral numbers '${WireNames.TIMESTAMP}' and '${WireNames.TTL_MS}'",
                        excerpt = text.excerpt(),
                        messageType = messageType,
                    ),
                )
            }
            return DecodedFrame.Push(RuntimePush.DomainEvent(domain, event, CompactJson.write(inner)), violations)
        }

        /** The published event of [inner], read by member name, or null if a member is missing or mistyped. */
        private fun publishedEvent(inner: WireValue.WireObject): PublishedEvent? =
            PublishedEvent(
                name = inner.stringMember(WireNames.EVENT) ?: return null,
                payload = inner.stringMember(WireNames.PAYLOAD) ?: return null,
                payloadFormat = inner.stringMember(WireNames.PAYLOAD_FORMAT) ?: return null,
                source = inner.stringMember(WireNames.SOURCE) ?: return null,
                timestamp = inner.integralMember(WireNames.TIMESTAMP) ?: return null,
                ttlMs = inner.integralMember(WireNames.TTL_MS) ?: return null,
            )

        /** A malformed payload: the member [name] is not [what]. */
        private fun missing(name: String, what: String): DecodedFrame =
            malformed("the payload's member '$name' is missing or not $what")

        /** A malformed payload without a revision, which the runtime with IDE support always writes. */
        private fun missingRevision(): DecodedFrame =
            malformed(
                "the payload's member '${WireNames.REVISION}' is missing or not an integral number; the client needs " +
                    "the runtime with IDE support",
            )

        /** A [ViolationKind.MALFORMED_PAYLOAD] with [detail]. */
        private fun malformed(detail: String): DecodedFrame = violation(ViolationKind.MALFORMED_PAYLOAD, detail)

        /** A violation of [kind] with [detail] and what could be salvaged. */
        private fun violation(kind: ViolationKind, detail: String): DecodedFrame =
            DecodedFrame.Violation(ProtocolViolation(kind, detail, text.excerpt(), messageType, objectId))
    }

    private companion object {
        /** The message type in a frame that is not JSON, if it can be found as a plain string member. */
        val SALVAGED_MESSAGE_TYPE = Regex(""""${WireNames.MESSAGE_TYPE}"\s*:\s*"([^"\\]*)"""")

        /** The object id in a frame that is not JSON, if it can be found as a plain string member. */
        val SALVAGED_OBJECT_ID = Regex(""""${WireNames.OBJECT_ID}"\s*:\s*"([^"\\]*)"""")
    }
}
