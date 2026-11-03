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
package io.karpfen.ide.runtime.protocol.transport

/** Where a transport ran out of time. */
public enum class TransportPhase {
    /** While connecting: nothing was sent. */
    CONNECTING,

    /** After the request was handed to the connection, while waiting for the answer: it may have been applied. */
    AWAITING_RESPONSE,
}

/**
 * Why a transport got no answer or could not send, from the client's side: whether the runtime may have received what
 * was sent decides how the caller goes on.
 */
public sealed interface TransportFailure {
    /** What the transport observed, in words, such as the message of the underlying exception. */
    public val cause: String

    /**
     * The runtime could not be reached: the connection was refused, the host was not found, or connecting failed
     * otherwise. Nothing was sent.
     */
    public data class Unreachable(override val cause: String) : TransportFailure

    /** The connection broke after the request was handed over: the runtime may have applied it. */
    public data class Interrupted(override val cause: String) : TransportFailure

    /**
     * The transport ran out of time.
     *
     * @property phase whether it was still connecting (nothing sent) or awaiting the answer (maybe applied).
     */
    public data class TimedOut(public val phase: TransportPhase, override val cause: String) : TransportFailure
}
