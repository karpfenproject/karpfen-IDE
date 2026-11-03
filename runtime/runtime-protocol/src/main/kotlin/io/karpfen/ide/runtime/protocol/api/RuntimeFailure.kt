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
package io.karpfen.ide.runtime.protocol.api

import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.message.ProtocolViolation

/** How far a failed call got, which decides whether the runtime may have applied it. */
public enum class Reach {
    /** Nothing was sent: the runtime did not apply the call. */
    NOT_SENT,

    /** The request was handed over but no answer came: the runtime may or may not have applied it. */
    MAYBE_APPLIED,

    /** The runtime answered: what it did is known from the answer. */
    ANSWERED,
}

/** Why a call of a [RuntimeRoute] did not succeed. Immutable. */
public sealed interface RuntimeFailure {
    /** The route that was called. */
    public val route: RuntimeRoute

    /** How far the call got. */
    public val reach: Reach

    /** The failure as one plain sentence, naming the route. */
    public fun describe(): String

    /**
     * The runtime could not be reached; nothing was sent.
     *
     * @property cause what the transport observed.
     */
    public data class Unreachable(override val route: RuntimeRoute, public val cause: String) : RuntimeFailure {
        override val reach: Reach get() = Reach.NOT_SENT

        override fun describe(): String = sentence("The runtime could not be reached for ${call(route)}: $cause")
    }

    /**
     * The request was handed over but no answer came, in time or at all.
     *
     * @property cause what the transport observed.
     * @property timedOut true if the client's timeout of the route expired, false if the connection broke.
     */
    public data class NoAnswer(
        override val route: RuntimeRoute,
        public val cause: String,
        public val timedOut: Boolean,
    ) : RuntimeFailure {
        override val reach: Reach get() = Reach.MAYBE_APPLIED

        override fun describe(): String =
            if (timedOut) {
                "The runtime did not answer ${call(route)} in time, so the call may or may not have been applied."
            } else {
                "The connection broke during ${call(route)} (${cause.trimEnd('.')}), so the call may or may not have been applied."
            }
    }

    /**
     * The runtime refused the call with 400 or 409 and one of its texts.
     *
     * @property status the HTTP status, 400 or 409.
     * @property kind the kind of the text, [RejectionKind.CONTENT_REJECTED] or [RejectionKind.UNCLASSIFIED] if it is
     *   none of the known texts.
     * @property message the body, the runtime's text.
     * @property captured the placeholder values of the matched text, such as the parameter name of a missing parameter;
     *   empty for a text without placeholders or an unknown text.
     */
    public data class Rejected(
        override val route: RuntimeRoute,
        public val status: Int,
        public val kind: RejectionKind,
        public val message: String,
        public val captured: Map<String, String>,
    ) : RuntimeFailure {
        override val reach: Reach get() = Reach.ANSWERED

        override fun describe(): String = sentence("The runtime rejected ${call(route)} with status $status: $message")
    }

    /**
     * The runtime failed with an internal error (500).
     *
     * @property message the body, the message of the exception the runtime ran into.
     */
    public data class ServerFault(override val route: RuntimeRoute, public val message: String) : RuntimeFailure {
        override val reach: Reach get() = Reach.ANSWERED

        override fun describe(): String = sentence("The runtime failed with an internal error on ${call(route)}: $message")
    }

    /**
     * The runtime answered with a status the route never answers, such as 404 for a route a runtime without IDE
     * support lacks.
     *
     * @property status the HTTP status.
     * @property body the body as it came.
     */
    public data class UnexpectedStatus(override val route: RuntimeRoute, public val status: Int, public val body: String) : RuntimeFailure {
        override val reach: Reach get() = Reach.ANSWERED

        override fun describe(): String {
            val quoted = body.excerpt().let { if (it.isEmpty()) "" else ": $it" }
            return sentence("The runtime answered ${call(route)} with the unexpected status $status$quoted")
        }
    }

    /**
     * The runtime answered with a success, but its body broke the protocol.
     *
     * @property violation how.
     */
    public data class ProtocolViolated(override val route: RuntimeRoute, public val violation: ProtocolViolation) : RuntimeFailure {
        override val reach: Reach get() = Reach.ANSWERED

        override fun describe(): String = sentence("The runtime's answer to ${call(route)} broke the protocol: ${violation.detail}")
    }
}

/** The longest part of a body that [RuntimeFailure.describe] quotes. */
private const val DESCRIBED_BODY_LENGTH = 200

/** The route as a sentence names it: its verb and path, such as `POST /runEnvironment`. */
private fun call(route: RuntimeRoute): String = "${route.verb} ${route.path}"

/** [text] ending with a full stop, unless it already ends with one, a question mark or an exclamation mark. */
private fun sentence(text: String): String = if (text.endsWith('.') || text.endsWith('?') || text.endsWith('!')) text else "$text."

/** The start of this body on one line, at most [DESCRIBED_BODY_LENGTH] characters. */
private fun String.excerpt(): String = trim().replace(Regex("\\s+"), " ").take(DESCRIBED_BODY_LENGTH)
