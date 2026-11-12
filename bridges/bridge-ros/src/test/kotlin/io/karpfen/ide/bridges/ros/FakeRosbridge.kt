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
package io.karpfen.ide.bridges.ros

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

/**
 * Stands in for a rosbridge server in the tests. It is a WebSocket server on a free port of this machine that keeps
 * what a client says and says what a test tells it to. It answers nothing on its own and knows nothing of ROS.
 */
class FakeRosbridge {
    private val sessions = CopyOnWriteArrayList<WebSocketSession>()
    private var server: EmbeddedServer<*, *>? = null

    /** What the clients said so far, one text frame each, in the order they arrived. */
    val received = LinkedBlockingQueue<String>()

    /** The port the server listens on, known once [start] returned. */
    var port: Int = 0
        private set

    /** The address a client connects to. */
    val url: String get() = "ws://127.0.0.1:$port"

    /** Starts the server on a free port. */
    fun start() {
        val started = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/") {
                    sessions += this
                    try {
                        for (frame in incoming) if (frame is Frame.Text) received += frame.readText()
                    } finally {
                        sessions -= this
                    }
                }
            }
        }
        started.start(wait = false)
        port = runBlocking { started.engine.resolvedConnectors().first().port }
        server = started
    }

    /** Says [text] to every connected client, as ROS does when something is published on a subscribed topic. */
    fun say(text: String) = runBlocking { sessions.forEach { it.send(Frame.Text(text)) } }

    /** Closes the connection of every client, as a server that goes down does. */
    fun hangUp() = runBlocking { sessions.forEach { it.close() } }

    /** Stops the server. */
    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        server = null
    }
}
