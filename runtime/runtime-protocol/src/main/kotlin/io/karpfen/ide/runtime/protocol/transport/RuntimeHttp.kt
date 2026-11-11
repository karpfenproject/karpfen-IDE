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

import io.karpfen.ide.runtime.protocol.catalogue.HttpVerb

/**
 * One HTTP request to the runtime, as text.
 *
 * @property verb the HTTP method.
 * @property path the route's path below the runtime's base URL, such as `/setModel`.
 * @property query the query parameters in the order they are sent, with unencoded values. The transport percent-encodes
 *   every value itself. It keeps unreserved characters and writes every other byte of the UTF-8 encoding as `%XX`, so a
 *   space becomes `%20`.
 * @property body the UTF-8 text of the request body, sent as `text/plain; charset=UTF-8`. It is present exactly for
 *   [HttpVerb.PUT].
 * @throws IllegalArgumentException if a body is given for a GET or POST, or none for a PUT.
 */
public data class TextRequest(
    public val verb: HttpVerb,
    public val path: String,
    public val query: List<Pair<String, String>>,
    public val body: String?,
) {
    init {
        require((verb == HttpVerb.PUT) == (body != null)) { "a $verb request to $path ${if (body == null) "needs" else "takes no"} body" }
    }
}

/** How an HTTP exchange with the runtime ended. */
public sealed interface HttpOutcome {
    /**
     * The runtime answered.
     *
     * @property status the HTTP status code.
     * @property body the response body, read as UTF-8 text.
     * @property contentType the `Content-Type` header, or null if the answer had none. The client never relies on it.
     */
    public data class Answered(public val status: Int, public val body: String, public val contentType: String?) : HttpOutcome

    /**
     * No answer arrived.
     *
     * @property failure why, and whether the request was handed over at all.
     */
    public data class Failed(public val failure: TransportFailure) : HttpOutcome
}

/**
 * The HTTP seam of the client. It sends one [TextRequest] to the runtime and reports how the exchange ended. The Ktor
 * adapter implements it in production. The fake runtime and test doubles implement it in tests.
 *
 * Implementations may be called from any coroutine, also concurrently. They never block a thread while they wait for
 * the network, and they never hold a lock across the exchange.
 *
 * A transport failure is an [HttpOutcome.Failed], never an exception. [TransportFailure.Unreachable] means nothing was
 * sent. [TransportFailure.Interrupted] means the request may have reached the runtime. A `CancellationException` of the
 * caller is rethrown and never turned into an outcome. Every answer is reported as it is, whatever its status, and the
 * caller interprets statuses and bodies. The transport encodes the query values exactly once, and it sends the body as
 * UTF-8 `text/plain`.
 */
public fun interface RuntimeHttp {
    /** Sends [request] and reports the answer or the failure. */
    public suspend fun exchange(request: TextRequest): HttpOutcome
}
