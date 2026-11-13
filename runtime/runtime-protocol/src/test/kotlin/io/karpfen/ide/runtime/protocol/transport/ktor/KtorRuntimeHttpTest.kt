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
import io.karpfen.ide.runtime.protocol.transport.TextRequest
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.withCharset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [KtorRuntimeHttp] over Ktor's mock engine. The URL is built from the endpoint's base and the request's path,
 * and the client encodes the query itself. PUT bodies and the message of a POST to a feature go as UTF-8 `text/plain`,
 * and other POST and GET requests have no body. Every answer is passed through with its status, its UTF-8 text and its
 * content type. Failures to connect count as unreachable, and failures after the request was handed over count as
 * interruptions. The caller's cancellation is rethrown.
 */
class KtorRuntimeHttpTest {

    /** The runtime the requests go to. */
    private val endpoint = RuntimeEndpoint.parse("http://127.0.0.1:8080")

    /** Runs [test] with the HTTP seam over a mock engine that answers with [handler], and the engine to inspect. */
    private suspend fun withHttp(handler: MockRequestHandler, test: suspend (KtorRuntimeHttp, MockEngine) -> Unit) {
        val engine = MockEngine(handler)
        HttpClient(engine).use { client -> test(KtorRuntimeHttp(client, endpoint), engine) }
    }

    /** The one request [engine] received. */
    private fun requestOf(engine: MockEngine): HttpRequestData = engine.requestHistory.single()

    @Test
    fun `the URL is the endpoint's base with the route's path and the query encoded by the client`() = runTest {
        withHttp({ respond("") }) { http, engine ->
            http.exchange(
                TextRequest(
                    HttpVerb.POST,
                    "/setActiveState",
                    listOf("envKey" to "env-1761560123456", "modelElement" to "APB 2101", "state" to "full power"),
                    null,
                ),
            )

            val request = requestOf(engine)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/setActiveState", request.url.encodedPath)
            assertEquals("envKey=env-1761560123456&modelElement=APB%202101&state=full%20power", request.url.encodedQuery)
            assertEquals(
                "http://127.0.0.1:8080/setActiveState?envKey=env-1761560123456&modelElement=APB%202101&state=full%20power",
                request.url.toString(),
            )
        }
    }

    @Test
    fun `query values with structuring, plus and non-ASCII characters arrive unchanged`() = runTest {
        val value = "a b&c=d%e+f/ü – living room"
        withHttp({ respond("") }) { http, engine ->
            http.exchange(TextRequest(HttpVerb.GET, "/observatory/statemachine", listOf("envKey" to "env-1", "modelElement" to value), null))

            val request = requestOf(engine)
            assertEquals("envKey=env-1&modelElement=a%20b%26c%3Dd%25e%2Bf%2F%C3%BC%20%E2%80%93%20living%20room", request.url.encodedQuery)
            assertEquals(value, request.url.parameters["modelElement"])
            assertEquals(HttpMethod.Get, request.method)
        }
    }

    @Test
    fun `a PUT body is sent as UTF-8 text with the charset in its content type`() = runTest {
        val model = "make object \"lamp\":\"Lamp\" { label: \"on – off\" }"
        withHttp({ respond("") }) { http, engine ->
            http.exchange(TextRequest(HttpVerb.PUT, "/setModel", listOf("envKey" to "env-1"), model))

            val request = requestOf(engine)
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(ContentType.Text.Plain.withCharset(Charsets.UTF_8), request.body.contentType)
            assertEquals("text/plain; charset=UTF-8", request.body.contentType.toString())
            assertTrue(model.encodeToByteArray().contentEquals(request.body.toByteArray()))
        }
    }

    @Test
    fun `the message of a POST to a feature is sent as UTF-8 text`() = runTest {
        withHttp({ respond("execution paused") }) { http, engine ->
            val answer = http.exchange(
                TextRequest(HttpVerb.POST, "/sendMessageToFeature", listOf("envKey" to "env-1", "featureName" to "TickByTick"), "pause"),
            )

            val request = requestOf(engine)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("text/plain; charset=UTF-8", request.body.contentType.toString())
            assertEquals("pause", request.body.toByteArray().decodeToString())
            assertEquals("execution paused", assertIs<HttpOutcome.Answered>(answer).body)
        }
    }

    @Test
    fun `POST and GET requests without a body carry none`() = runTest {
        withHttp({ respond("") }) { http, engine ->
            http.exchange(TextRequest(HttpVerb.POST, "/runEnvironment", listOf("envKey" to "env-1"), null))
            http.exchange(TextRequest(HttpVerb.GET, "/health", emptyList(), null))

            engine.requestHistory.forEach { request ->
                assertNull(request.body.contentType)
                assertEquals(0, request.body.toByteArray().size)
            }
            assertEquals("", engine.requestHistory.last().url.encodedQuery)
        }
    }

    @Test
    fun `every answer is passed through with its status, its UTF-8 text and its content type`() = runTest {
        val answers = ArrayDeque(
            listOf<MockRequestHandler>(
                { respond("Environment with key  does not exist", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "text/plain; charset=UTF-8")) },
                { respond("on – off".encodeToByteArray(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain")) },
                { respond("", HttpStatusCode.NotFound, headersOf()) },
                { respond("Unexpected error", HttpStatusCode.InternalServerError, headersOf()) },
            ),
        )
        withHttp({ request -> answers.removeFirst()(this, request) }) { http, _ ->
            val health = TextRequest(HttpVerb.GET, "/health", emptyList(), null)

            assertEquals(
                HttpOutcome.Answered(400, "Environment with key  does not exist", "text/plain; charset=UTF-8"),
                http.exchange(TextRequest(HttpVerb.POST, "/runEnvironment", listOf("envKey" to ""), null)),
            )
            assertEquals(HttpOutcome.Answered(200, "on – off", "text/plain"), http.exchange(health))
            assertEquals(HttpOutcome.Answered(404, "", null), http.exchange(health))
            assertEquals(HttpOutcome.Answered(500, "Unexpected error", null), http.exchange(health))
        }
    }

    @Test
    fun `a failure to connect is unreachable`() = runTest {
        withHttp({ throw ConnectException("Connection refused") }) { http, _ ->
            assertEquals(
                HttpOutcome.Failed(TransportFailure.Unreachable("Connection refused")),
                http.exchange(TextRequest(HttpVerb.POST, "/createEnvironment", emptyList(), null)),
            )
        }
    }

    @Test
    fun `a failure after the request was handed over is an interruption`() = runTest {
        withHttp({ throw IOException("Connection reset by peer") }) { http, _ ->
            assertEquals(
                HttpOutcome.Failed(TransportFailure.Interrupted("Connection reset by peer")),
                http.exchange(TextRequest(HttpVerb.POST, "/stopEnvironment", listOf("envKey" to "env-1"), null)),
            )
        }
    }

    @Test
    fun `the caller's cancellation is rethrown`() = runTest {
        val reached = CompletableDeferred<Unit>()
        withHttp({
            reached.complete(Unit)
            awaitCancellation()
        }) { http, _ ->
            val call = async { http.exchange(TextRequest(HttpVerb.GET, "/observatory/model", listOf("envKey" to "env-1"), null)) }
            reached.await()
            call.cancel()

            assertFailsWith<CancellationException> { call.await() }
            assertTrue(call.isCancelled)
        }
    }
}
