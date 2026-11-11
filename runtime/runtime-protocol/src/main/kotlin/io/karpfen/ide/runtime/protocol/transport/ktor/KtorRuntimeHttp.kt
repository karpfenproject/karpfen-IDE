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
package io.karpfen.ide.runtime.protocol.transport.ktor

import io.karpfen.ide.runtime.protocol.catalogue.HttpVerb
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.transport.HttpOutcome
import io.karpfen.ide.runtime.protocol.transport.RuntimeHttp
import io.karpfen.ide.runtime.protocol.transport.TextRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import io.ktor.http.withCharset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The HTTP seam over a Ktor [client]. The URL is the endpoint's base with the request's path. The query is appended as
 * parameters that the client's own [QueryEncoding] has already encoded. A PUT body is sent as
 * `text/plain; charset=UTF-8`. Every response body is read as UTF-8 text, whatever its content type says.
 *
 * Exceptions become [HttpOutcome.Failed] through [TransportFailures]. A cancellation of the caller is rethrown. Ktor
 * also raises a cancellation for a broken connection while the caller is still active. That one is a failure like any
 * other.
 *
 * @param client the Ktor client, shared with the WebSocket seam. Its owner closes it.
 * @param endpoint the runtime.
 */
internal class KtorRuntimeHttp(private val client: HttpClient, private val endpoint: RuntimeEndpoint) : RuntimeHttp {

    override suspend fun exchange(request: TextRequest): HttpOutcome =
        try {
            val response = client.request {
                method = methodOf(request.verb)
                url {
                    takeFrom(endpoint.httpBase)
                    encodedPath = request.path
                    request.query.forEach { (name, value) ->
                        encodedParameters.append(QueryEncoding.encode(name), QueryEncoding.encode(value))
                    }
                }
                request.body?.let { setBody(TextContent(it, ContentType.Text.Plain.withCharset(Charsets.UTF_8))) }
            }
            HttpOutcome.Answered(response.status.value, response.bodyAsBytes().decodeToString(), response.headers[HttpHeaders.ContentType])
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            HttpOutcome.Failed(TransportFailures.classify(cancelled))
        } catch (failure: Exception) {
            HttpOutcome.Failed(TransportFailures.classify(failure))
        }

    /** The Ktor method of [verb]. */
    private fun methodOf(verb: HttpVerb): HttpMethod =
        when (verb) {
            HttpVerb.GET -> HttpMethod.Get
            HttpVerb.POST -> HttpMethod.Post
            HttpVerb.PUT -> HttpMethod.Put
        }
}
