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

/** How something the runtime sent departs from its protocol. */
public enum class ViolationKind {
    /** A frame that is not JSON as the runtime writes it. */
    UNDECODABLE_FRAME,

    /** A binary frame, which the runtime never sends. */
    BINARY_FRAME,

    /** A push whose envelope names another environment or client than the connection's. */
    FOREIGN_ENVELOPE,

    /** An envelope or payload with a missing member or a member of the wrong type. */
    MALFORMED_PAYLOAD,

    /** An `objectChanged` push whose `objectId` differs from the `__id__` of its value. */
    ID_MISMATCH,

    /** A success answer whose body is not what the route answers. */
    UNEXPECTED_BODY,
}

/**
 * A departure from the runtime's protocol: what was received, why it does not fit, and what could be salvaged.
 *
 * @property kind the kind of departure.
 * @property detail what does not fit, in words.
 * @property excerpt the start of what was received, at most its first 200 characters.
 * @property messageType the message type, if it could be read.
 * @property objectId the object id, if it could be read.
 * @throws IllegalArgumentException if [excerpt] is longer than 200 characters.
 */
public data class ProtocolViolation(
    public val kind: ViolationKind,
    public val detail: String,
    public val excerpt: String,
    public val messageType: String? = null,
    public val objectId: String? = null,
) {
    init {
        require(excerpt.length <= EXCERPT_LENGTH) { "an excerpt holds at most $EXCERPT_LENGTH characters, not ${excerpt.length}" }
    }

    internal companion object {
        /** The longest excerpt; a decoder takes the first this many characters of what it received. */
        const val EXCERPT_LENGTH: Int = 200
    }
}

/** What one frame from the runtime decodes to. */
public sealed interface DecodedFrame {
    /**
     * A push, possibly with violations that did not prevent decoding it, such as an id mismatch.
     *
     * @property push the push.
     * @property violations the violations found along the way, in the order they were found.
     */
    public data class Push(public val push: RuntimePush, public val violations: List<ProtocolViolation>) : DecodedFrame

    /**
     * A frame that could not be decoded to a push.
     *
     * @property violation why.
     */
    public data class Violation(public val violation: ProtocolViolation) : DecodedFrame
}
