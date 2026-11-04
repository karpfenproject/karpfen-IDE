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
package io.karpfen.ide.runtime.protocol.fake

import io.karpfen.ide.runtime.protocol.catalogue.HttpVerb
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText

/**
 * An answer a test programs for the [FakeRuntime], exactly as the runtime would send it: a status, a body and a content
 * type. The body is a literal text, usually one of the runtime goldens, written with the runtime wire writer or
 * rendered from a [RuntimeText].
 *
 * @property status the HTTP status.
 * @property body the response body.
 * @property contentType the `Content-Type` header, or null for none.
 */
public data class FakeResponse(public val status: Int, public val body: String = "", public val contentType: String? = null) {
    public companion object {
        /** The status of a success. */
        public const val OK: Int = 200

        /** The status of a rejected argument, `IllegalArgumentException` on the runtime. */
        public const val BAD_REQUEST: Int = 400

        /** The status of an unknown path. */
        public const val NOT_FOUND: Int = 404

        /** The status of a rejected state, `IllegalStateException` on the runtime. */
        public const val CONFLICT: Int = 409

        /** The status of any other exception on the runtime. */
        public const val SERVER_ERROR: Int = 500

        /** The content type of the runtime's text answers, `call.respond(status, <String>)`. */
        public const val TEXT: String = "text/plain; charset=UTF-8"

        /** The content type of the model snapshot. */
        public const val JSON: String = "application/json; charset=UTF-8"

        /** A success with [body] as a text answer. */
        public fun ok(body: String = ""): FakeResponse = FakeResponse(OK, body, TEXT)

        /** A success with the JSON [body], such as a model snapshot. */
        public fun json(body: String): FakeResponse = FakeResponse(OK, body, JSON)

        /** A rejection with [status] and the runtime's [text] rendered with [values]. */
        public fun rejected(status: Int, text: RuntimeText, values: Map<String, String> = emptyMap()): FakeResponse =
            FakeResponse(status, text.render(values), TEXT)
    }
}

/**
 * A request the [FakeRuntime] received, as the client sent it.
 *
 * @property route the catalogue route of the request's verb and path, or null for a path the catalogue does not know.
 * @property verb the HTTP method.
 * @property path the path below the runtime's base URL.
 * @property parameters the query parameters in the order they were sent, with unencoded values.
 * @property body the request body, present exactly for a PUT.
 */
public data class FakeRequest(
    public val route: RuntimeRoute?,
    public val verb: HttpVerb,
    public val path: String,
    public val parameters: List<Pair<String, String>>,
    public val body: String?,
) {
    /** The value of the first query parameter [name], or null if the request has none of that name. */
    public fun parameter(name: String): String? = parameters.firstOrNull { it.first == name }?.second

    /** True if every entry of [where] is a query parameter of the request with that value. */
    public fun matches(where: Map<String, String>): Boolean = where.all { (name, value) -> parameter(name) == value }
}

/**
 * A close frame with its code and reason, as a test programs it for a handshake or reads it from a link the client
 * closed.
 *
 * @property code the close code.
 * @property reason the close reason.
 */
public data class FakeClose(public val code: Int, public val reason: String)
