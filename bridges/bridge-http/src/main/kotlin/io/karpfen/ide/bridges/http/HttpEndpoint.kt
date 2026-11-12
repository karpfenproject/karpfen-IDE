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

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.karpfen.ide.bridges.api.BridgeEndpoint
import io.karpfen.ide.bridges.api.Delivery
import io.karpfen.ide.bridges.api.ExternalMessage
import io.karpfen.ide.bridges.api.JsonText
import io.karpfen.ide.runtime.protocol.message.WireValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The HTTP side of a bridge. It is a small server for the inbound routes and a client for the outbound ones.
 *
 * The server listens on the host and port of [settings]. A `POST` or `PUT` to the path of an inbound route is one
 * message for that route, with the body of the request as its body. The server answers at once with 202, because the
 * model takes the event on a later tick. It does not wait for that. A path without a route gets 404, and another
 * method on the path of a route gets 405. A `GET` of `/` lists the paths the bridge listens on, so someone with only
 * the address can find out what to post where.
 *
 * The client posts each outbound message to the URL of its route as JSON. An answer in the 200 range counts as
 * delivered. Anything else, a timeout or a connection that cannot be made counts as failed, with the reason in words.
 *
 * There is no authentication. The default host is the local machine, so by default only programs on the same machine
 * reach the bridge.
 *
 * @param ioDispatcher where the server is started and stopped, which blocks for a moment.
 */
public class HttpEndpoint(
    private val settings: HttpSettings,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BridgeEndpoint {

    private val arriving = MutableSharedFlow<ExternalMessage>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.timeoutMs)).build() }

    @Volatile
    private var server: HttpServer? = null

    override val incoming: Flow<ExternalMessage> get() = arriving

    /** The port the server listens on, or null while it is not open. It differs from the settings for port 0. */
    public val port: Int? get() = server?.address?.port

    override suspend fun open(addresses: Set<String>): Delivery = withContext(ioDispatcher) {
        if (server != null) return@withContext Delivery.Delivered
        val routes = addresses.associateBy(::normalized)
        try {
            val created = HttpServer.create(InetSocketAddress(settings.host, settings.port), 0)
            created.createContext("/") { exchange -> handle(exchange, routes) }
            created.start()
            server = created
            Delivery.Delivered
        } catch (e: BindException) {
            Delivery.Failed("Port ${settings.port} on ${settings.host} cannot be used: ${e.message ?: "it is taken"}.")
        } catch (e: IOException) {
            Delivery.Failed("The HTTP server could not start on ${settings.host}:${settings.port}: ${e.message ?: e}.")
        } catch (e: IllegalArgumentException) {
            Delivery.Failed("${settings.host}:${settings.port} is no address to listen on.")
        }
    }

    override suspend fun publish(message: ExternalMessage): Delivery {
        val request = try {
            HttpRequest.newBuilder(URI.create(message.address))
                .timeout(Duration.ofMillis(settings.timeoutMs))
                .header("Content-Type", JSON)
                .POST(HttpRequest.BodyPublishers.ofString(message.body))
                .build()
        } catch (e: IllegalArgumentException) {
            return Delivery.Failed("${message.address} is no URL that can be posted to.")
        }
        return try {
            val status = client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await().statusCode()
            if (status in SUCCESS) Delivery.Delivered else Delivery.Failed("${message.address} answered with HTTP $status.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Delivery.Failed("${message.address} could not be reached: ${e.message ?: e.javaClass.simpleName}.")
        }
    }

    override suspend fun close(): Unit = withContext(ioDispatcher) {
        server?.stop(0)
        server = null
    }

    /** Answers one request. [routes] are the addresses of the inbound routes by their path. */
    private fun handle(exchange: HttpExchange, routes: Map<String, String>) {
        exchange.use {
            val path = exchange.requestURI.path
            val method = exchange.requestMethod.uppercase()
            val address = routes[path]
            when {
                method == "GET" && path == "/" -> answer(exchange, OK, listing(routes.keys))
                address == null -> answer(exchange, NOT_FOUND, error("The bridge has no route for $path."))
                method != "POST" && method != "PUT" -> {
                    exchange.responseHeaders.add("Allow", "POST, PUT")
                    answer(exchange, METHOD_NOT_ALLOWED, error("Send a message to $path with POST or PUT."))
                }
                else -> {
                    val body = exchange.requestBody.readNBytes(MAX_BODY + 1)
                    if (body.size > MAX_BODY) {
                        answer(exchange, TOO_LARGE, error("The message is larger than $MAX_BODY bytes."))
                    } else {
                        arriving.tryEmit(ExternalMessage(address, body.decodeToString()))
                        answer(exchange, ACCEPTED, JsonText.write(JsonText.objectOf("accepted" to WireValue.WireBoolean(true))))
                    }
                }
            }
        }
    }

    private fun answer(exchange: HttpExchange, status: Int, json: String) {
        val bytes = json.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", JSON)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun listing(paths: Collection<String>): String =
        JsonText.write(JsonText.objectOf("inbound" to WireValue.WireArray(paths.sorted().map(WireValue::WireString))))

    private fun error(message: String): String = JsonText.write(JsonText.objectOf("error" to WireValue.WireString(message)))

    /** [address] as a request path, which starts with a slash. */
    private fun normalized(address: String): String = if (address.startsWith("/")) address else "/$address"

    private companion object {
        const val JSON = "application/json"
        const val OK = 200
        const val ACCEPTED = 202
        const val NOT_FOUND = 404
        const val METHOD_NOT_ALLOWED = 405
        const val TOO_LARGE = 413
        val SUCCESS = 200..299

        /** The largest body of an inbound message in bytes. */
        const val MAX_BODY = 1_048_576

        /** How many inbound messages may wait until the bridge reads them. Older ones are dropped beyond that. */
        const val BUFFER = 256
    }
}
