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

import io.karpfen.ide.bridges.api.BridgeEndpoint
import io.karpfen.ide.bridges.api.Delivery
import io.karpfen.ide.bridges.api.ExternalMessage
import io.karpfen.ide.bridges.api.JsonText
import io.karpfen.ide.runtime.protocol.message.WireField
import io.karpfen.ide.runtime.protocol.message.WireValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The ROS side of a bridge: one WebSocket connection to a rosbridge server. An address of a route is a ROS topic.
 *
 * Opening connects, subscribes to the topic of every inbound route and advertises the topic of every outbound route
 * with the type of its messages. A message that ROS publishes on a subscribed topic arrives in [incoming] with the
 * topic as its address and the message as JSON. A message that a route sends is published on its topic, turned into
 * a message of the type of that topic as [RosTypes.messageOf] describes.
 *
 * If the connection breaks, the endpoint connects again every few seconds and then subscribes and advertises anew.
 * Outbound messages fail in the meantime.
 *
 * @param settings where the rosbridge server is and whether it belongs to ROS 1.
 * @param subscriptions the type of the messages of each inbound topic, or null to let the server find it, which
 *   works only for a topic that exists in ROS already.
 * @param advertisements the type of the messages of each outbound topic, or null for [RosTypes.DEFAULT].
 * @param dispatcher where the connection is read and renewed.
 */
public class RosEndpoint(
    private val settings: RosSettings,
    private val subscriptions: Map<String, String?> = emptyMap(),
    private val advertisements: Map<String, String?> = emptyMap(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BridgeEndpoint {

    private val arriving = MutableSharedFlow<ExternalMessage>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile
    private var connection: Connection? = null

    @Volatile
    private var session: DefaultClientWebSocketSession? = null

    @Volatile
    private var topics: Set<String> = emptySet()

    override val incoming: Flow<ExternalMessage> get() = arriving

    override suspend fun open(addresses: Set<String>): Delivery {
        if (connection != null) return Delivery.Delivered
        topics = addresses
        val opened = Connection(HttpClient(CIO) { install(WebSockets) }, CoroutineScope(SupervisorJob() + dispatcher))
        val failure = connect(opened.client)
        if (failure != null) {
            opened.client.close()
            return Delivery.Failed(failure)
        }
        opened.reader = opened.scope.launch { keepReading(opened.client) }
        connection = opened
        return Delivery.Delivered
    }

    override suspend fun publish(message: ExternalMessage): Delivery {
        val open = session ?: return Delivery.Failed("The ROS bridge is not connected.")
        val type = typeOf(advertisements[message.address])
        val text = JsonText.write(
            JsonText.objectOf(
                OP to WireValue.WireString(PUBLISH),
                TOPIC to WireValue.WireString(message.address),
                MSG to RosTypes.messageOf(message.body, type),
            ),
        )
        return when (val failure = attempt { open.send(Frame.Text(text)) }) {
            null -> Delivery.Delivered
            else -> Delivery.Failed("The message could not be sent to the rosbridge server: $failure")
        }
    }

    override suspend fun close() {
        val open = connection ?: return
        connection = null
        open.reader?.cancelAndJoin()
        session?.let { closing -> attempt { closing.close() } }
        session = null
        open.client.close()
    }

    /**
     * Connects with [client], subscribes and advertises. Returns null if that worked, and otherwise why it did not,
     * in one sentence.
     */
    private suspend fun connect(client: HttpClient): String? {
        val failure = attempt {
            val opened = withTimeout(settings.timeoutMs) { client.webSocketSession(settings.url) }
            announce(opened)
            session = opened
        }
        return when (failure) {
            null -> null
            TIMEOUT -> "The rosbridge server at ${settings.url} did not answer within ${settings.timeoutMs} ms."
            else -> "The rosbridge server at ${settings.url} cannot be reached: $failure"
        }
    }

    /** Tells the server which topics the bridge reads and which it writes. */
    private suspend fun announce(opened: DefaultClientWebSocketSession) {
        for (topic in topics) {
            val type = subscriptions[topic]?.let { RosTypes.written(it, settings.ros1) }
            opened.send(Frame.Text(JsonText.write(operation(SUBSCRIBE, topic, type))))
        }
        for ((topic, type) in advertisements) {
            opened.send(Frame.Text(JsonText.write(operation(ADVERTISE, topic, typeOf(type)))))
        }
    }

    /** Reads the connection until it ends, then connects again, for as long as the endpoint is open. */
    private suspend fun keepReading(client: HttpClient) {
        while (currentCoroutineContext().isActive) {
            session?.let { open ->
                attempt { for (frame in open.incoming) if (frame is Frame.Text) accept(frame.readText()) }
                session = null
            }
            delay(RECONNECT_MS)
            connect(client)
        }
    }

    /** Hands on what ROS published, if [text] is a message on a topic. Everything else the server says is ignored. */
    private fun accept(text: String) {
        val frame = JsonText.read(text) as? WireValue.WireObject ?: return
        if ((frame[OP] as? WireValue.WireString)?.value != PUBLISH) return
        val topic = (frame[TOPIC] as? WireValue.WireString)?.value ?: return
        arriving.tryEmit(ExternalMessage(topic, JsonText.write(frame[MSG] ?: WireValue.WireObject(emptyList()))))
    }

    /** The type of an outbound topic as the server expects it, [RosTypes.DEFAULT] if the route names none. */
    private fun typeOf(named: String?): String = RosTypes.written(named ?: RosTypes.DEFAULT, settings.ros1)

    private fun operation(op: String, topic: String, type: String?): WireValue.WireObject = WireValue.WireObject(
        listOfNotNull(
            OP to WireValue.WireString(op),
            TOPIC to WireValue.WireString(topic),
            type?.let { TYPE to WireValue.WireString(it) },
        ).map { WireField(it.first, it.second) },
    )

    /**
     * Runs [work] with the connection. Returns null if it went through, [TIMEOUT] if it ran out of time, and
     * otherwise what the connection said went wrong. Being cancelled from outside is not a failure and goes on up.
     */
    private suspend fun attempt(work: suspend () -> Unit): String? = try {
        withContext(dispatcher) { work() }
        null
    } catch (_: TimeoutCancellationException) {
        TIMEOUT
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failure.message ?: failure.javaClass.simpleName
    }

    /** What an open endpoint holds: the client, the scope of its reader, and the reader. */
    private class Connection(val client: HttpClient, val scope: CoroutineScope) {
        var reader: Job? = null
    }

    private companion object {
        const val OP = "op"
        const val TOPIC = "topic"
        const val TYPE = "type"
        const val MSG = "msg"
        const val SUBSCRIBE = "subscribe"
        const val ADVERTISE = "advertise"
        const val PUBLISH = "publish"

        /** What [attempt] returns when the work ran out of time. */
        const val TIMEOUT = "timeout"

        /** How long the endpoint waits before it connects again, in milliseconds. */
        const val RECONNECT_MS = 2_000L

        /** How many messages wait for the bridge before the oldest is dropped. */
        const val BUFFER = 256
    }
}
