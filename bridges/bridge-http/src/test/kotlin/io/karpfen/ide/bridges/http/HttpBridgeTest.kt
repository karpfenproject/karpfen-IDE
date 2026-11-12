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
package io.karpfen.ide.bridges.http

import com.sun.net.httpserver.HttpServer
import io.karpfen.ide.bridges.api.Bridge
import io.karpfen.ide.bridges.api.BridgeConfig
import io.karpfen.ide.bridges.api.BridgeKind
import io.karpfen.ide.bridges.api.BridgeState
import io.karpfen.ide.bridges.api.Delivery
import io.karpfen.ide.bridges.api.ExternalMessage
import io.karpfen.ide.bridges.api.JsonText
import io.karpfen.ide.bridges.api.ModelLink
import io.karpfen.ide.bridges.mapping.BridgeConfigFile
import io.karpfen.ide.bridges.mapping.RoutedBridge
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.EventPayload
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.message.WireValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests the HTTP bridge with real requests on the local machine. The bridge listens on a port the system picks, and
 * a small server of the test stands in for the system that outbound messages are posted to.
 */
class HttpBridgeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = HttpClient.newHttpClient()
    private val target = Target()
    private val endpoints = ArrayList<HttpEndpoint>()

    @AfterTest
    fun cleanUp() = runBlocking {
        endpoints.forEach { it.close() }
        target.stop()
        scope.cancel()
    }

    @Test
    fun `a post to the path of a route arrives as a message and is answered with 202`() = runBlocking {
        val endpoint = opened("/switch/press", "/dimmer")
        val arrived = Channel<ExternalMessage>(Channel.UNLIMITED)
        val reader = scope.launch(start = CoroutineStart.UNDISPATCHED) { endpoint.incoming.collect(arrived::send) }

        val response = request(endpoint, "POST", "/dimmer", """{"up": true}""")

        assertEquals(202 to """{"accepted":true}""", response.statusCode() to response.body())
        assertEquals(ExternalMessage("/dimmer", """{"up": true}"""), withTimeout(WAIT) { arrived.receive() })
        assertEquals(202, request(endpoint, "PUT", "/switch/press", "").statusCode(), "PUT works like POST")
        reader.cancel()
    }

    @Test
    fun `a path without a route, another method and the root are answered in words`() = runBlocking {
        val endpoint = opened("/switch/press", "dimmer")

        val unknown = request(endpoint, "POST", "/nowhere", "")
        val wrongMethod = request(endpoint, "GET", "/switch/press", null)
        val root = request(endpoint, "GET", "/", null)

        assertEquals(404 to """{"error":"The bridge has no route for /nowhere."}""", unknown.statusCode() to unknown.body())
        assertEquals(405, wrongMethod.statusCode())
        assertEquals("POST, PUT", wrongMethod.headers().firstValue("Allow").orElse(null))
        assertEquals(200 to """{"inbound":["/dimmer","/switch/press"]}""", root.statusCode() to root.body())
    }

    @Test
    fun `an address without a slash is still reported as the route wrote it`() = runBlocking {
        val endpoint = opened("dimmer")
        val arrived = Channel<ExternalMessage>(Channel.UNLIMITED)
        val reader = scope.launch(start = CoroutineStart.UNDISPATCHED) { endpoint.incoming.collect(arrived::send) }

        request(endpoint, "POST", "/dimmer", "{}")

        assertEquals("dimmer", withTimeout(WAIT) { arrived.receive() }.address)
        reader.cancel()
    }

    @Test
    fun `a port that is taken makes the endpoint fail to open with the reason`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { taken ->
            val endpoint = HttpEndpoint(HttpSettings(port = taken.localPort)).also(endpoints::add)

            val outcome = endpoint.open(setOf("/x"))

            val failed = assertIs<Delivery.Failed>(outcome)
            assertTrue(failed.reason.startsWith("Port ${taken.localPort} on 127.0.0.1 cannot be used"), failed.reason)
        }
    }

    @Test
    fun `an outbound message is posted as JSON, and what goes wrong is told in words`() = runBlocking {
        val endpoint = HttpEndpoint(HttpSettings(timeoutMs = 2_000))

        assertEquals(Delivery.Delivered, endpoint.publish(ExternalMessage(target.url("/lamp/watts"), "30")))
        assertEquals(Posted("/lamp/watts", "30", "application/json"), target.next())

        target.status = 500
        assertEquals(Delivery.Failed("${target.url("/lamp/watts")} answered with HTTP 500."), endpoint.publish(ExternalMessage(target.url("/lamp/watts"), "30")))

        val closedPort = ServerSocket(0).use { it.localPort }
        val unreachable = assertIs<Delivery.Failed>(endpoint.publish(ExternalMessage("http://127.0.0.1:$closedPort/x", "1")))
        assertTrue(unreachable.reason.startsWith("http://127.0.0.1:$closedPort/x could not be reached"), unreachable.reason)

        assertEquals(Delivery.Failed("not a url is no URL that can be posted to."), endpoint.publish(ExternalMessage("not a url", "1")))
    }

    @Test
    fun `a bridge file runs end to end, a request becomes an event and a change of the model a request`() = runBlocking {
        val text = """
            {
              "kind": "http",
              "settings": { "port": 0 },
              "inbound": [ { "from": "/dimmer", "event": "switch/dim", "payload": { "bright": "${'$'}.up" } } ],
              "outbound": [ { "property": "bulb/watts", "to": "${target.url("/lamp/watts")}" } ]
            }
        """.trimIndent()
        val config = checkNotNull(BridgeConfigFile.read(text).config)
        val link = RecordingLink()
        val endpoint = HttpEndpoint(HttpSettings.of(config.settings)).also(endpoints::add)
        val bridge: Bridge = RoutedBridge(config, endpoint, link, scope)

        bridge.start()
        assertEquals(BridgeState.RUNNING, bridge.status.value.state)

        request(endpoint, "POST", "/dimmer", """{"up": false}""")
        assertEquals(EventFrame("switch", "dim", EventPayload.Json("""{"bright":false}""")), link.sent.poll(WAIT, TimeUnit.MILLISECONDS))

        link.report(RuntimePush.ObjectChanged("bulb", 7, JsonText.read("""{"watts": 30, "lit": true}""") as WireValue.WireObject))
        assertEquals(Posted("/lamp/watts", "30", "application/json"), target.next())

        bridge.stop()
        assertEquals(BridgeState.STOPPED, bridge.status.value.state)
        assertEquals(null, endpoint.port, "the server is gone")
    }

    @Test
    fun `the factory builds a bridge for an http configuration only`() {
        val link = RecordingLink()

        assertNotNull(HttpBridge(BridgeConfig(BridgeKind.HTTP), link, scope))
        val refused = runCatching { HttpBridge(BridgeConfig(BridgeKind.MQTT), link, scope) }.exceptionOrNull()
        assertIs<IllegalArgumentException>(refused)
    }

    /** An endpoint that listens for [addresses] on a port the system picked. It is closed after the test. */
    private suspend fun opened(vararg addresses: String): HttpEndpoint {
        val endpoint = HttpEndpoint(HttpSettings(port = 0)).also(endpoints::add)
        assertEquals(Delivery.Delivered, endpoint.open(addresses.toSet()))
        return endpoint
    }

    private fun request(endpoint: HttpEndpoint, method: String, path: String, body: String?): HttpResponse<String> {
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${endpoint.port}$path")).method(method, publisher).build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** What the target server received in one request. */
    private data class Posted(val path: String, val body: String, val contentType: String?)

    /** A server that stands in for the system outbound messages go to. It records every request it gets. */
    private class Target {
        private val received = LinkedBlockingQueue<Posted>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.use {
                    received += Posted(exchange.requestURI.path, exchange.requestBody.readBytes().decodeToString(), exchange.requestHeaders.getFirst("Content-Type"))
                    exchange.sendResponseHeaders(status, -1)
                }
            }
            start()
        }

        @Volatile
        var status = 204

        fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"

        fun next(): Posted? = received.poll(WAIT, TimeUnit.MILLISECONDS)

        fun stop() = server.stop(0)
    }

    /** A model in memory that records the events a bridge sends. */
    private class RecordingLink : ModelLink {
        private val reported = MutableSharedFlow<RuntimePush>(extraBufferCapacity = 16)
        val sent = LinkedBlockingQueue<EventFrame>()

        override val pushes get() = reported

        override suspend fun send(event: EventFrame): Delivery {
            sent += event
            return Delivery.Delivered
        }

        suspend fun report(push: RuntimePush) {
            reported.subscriptionCount.first { it > 0 }
            reported.emit(push)
        }
    }

    private companion object {
        /** How long a test waits for something to arrive, in milliseconds. */
        const val WAIT = 5_000L
    }
}
