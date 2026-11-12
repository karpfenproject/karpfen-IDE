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

import io.karpfen.ide.bridges.api.Bridge
import io.karpfen.ide.bridges.api.BridgeState
import io.karpfen.ide.bridges.api.Delivery
import io.karpfen.ide.bridges.api.ExternalMessage
import io.karpfen.ide.bridges.api.JsonText
import io.karpfen.ide.bridges.api.ModelLink
import io.karpfen.ide.bridges.mapping.BridgeConfigFile
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests the ROS bridge against a WebSocket server inside the test that stands in for a rosbridge server. The tests
 * check what the bridge says in the rosbridge protocol and what it makes of what the server says.
 */
class RosBridgeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rosbridge = FakeRosbridge()
    private val endpoints = ArrayList<RosEndpoint>()

    @BeforeTest
    fun startServer() = rosbridge.start()

    @AfterTest
    fun stopServer() = runBlocking {
        endpoints.forEach { it.close() }
        rosbridge.stop()
        scope.cancel()
    }

    @Test
    fun `opening subscribes to the inbound topics and advertises the outbound ones with their types`() = runBlocking {
        val endpoint = endpoint(
            subscriptions = mapOf("/lamp/press" to "std_msgs/Empty", "/lamp/dim" to null),
            advertisements = mapOf("/lamp/watts" to "std_msgs/msg/Float64", "/lamp/state" to null),
        )

        assertEquals(Delivery.Delivered, endpoint.open(setOf("/lamp/press", "/lamp/dim")))

        assertEquals(
            setOf(
                """{"op":"subscribe","topic":"/lamp/press","type":"std_msgs/msg/Empty"}""",
                """{"op":"subscribe","topic":"/lamp/dim"}""",
                """{"op":"advertise","topic":"/lamp/watts","type":"std_msgs/msg/Float64"}""",
                """{"op":"advertise","topic":"/lamp/state","type":"std_msgs/msg/String"}""",
            ),
            (1..4).map { said() }.toSet(),
        )
    }

    @Test
    fun `a ROS 1 system gets its types without the category`() = runBlocking {
        val endpoint = endpoint(
            ros1 = true,
            subscriptions = mapOf("/lamp/press" to "std_msgs/msg/Empty"),
            advertisements = mapOf("/lamp/watts" to "std_msgs/msg/Float64", "/lamp/state" to null),
        )

        endpoint.open(setOf("/lamp/press"))

        assertEquals(
            setOf(
                """{"op":"subscribe","topic":"/lamp/press","type":"std_msgs/Empty"}""",
                """{"op":"advertise","topic":"/lamp/watts","type":"std_msgs/Float64"}""",
                """{"op":"advertise","topic":"/lamp/state","type":"std_msgs/String"}""",
            ),
            (1..3).map { said() }.toSet(),
        )
    }

    @Test
    fun `what ROS publishes on a subscribed topic arrives with the topic as its address`() = runBlocking {
        val endpoint = endpoint(subscriptions = mapOf("/lamp/dim" to "std_msgs/msg/Bool"))
        endpoint.open(setOf("/lamp/dim"))
        said()
        val arrived = Channel<ExternalMessage>(Channel.UNLIMITED)
        val reader = scope.launch(start = CoroutineStart.UNDISPATCHED) { endpoint.incoming.collect(arrived::send) }

        rosbridge.say("""{"op": "status", "level": "info", "msg": "hello"}""")
        rosbridge.say("""{"op": "publish", "topic": "/lamp/dim", "msg": {"data": true}}""")

        assertEquals(ExternalMessage("/lamp/dim", """{"data":true}"""), withTimeout(WAIT) { arrived.receive() })
        reader.cancel()
    }

    @Test
    fun `an outbound message is published on its topic as a message of the type of that topic`() = runBlocking {
        val endpoint = endpoint(advertisements = mapOf("/lamp/watts" to "std_msgs/msg/Float64", "/lamp/state" to null))
        endpoint.open(emptySet())
        repeat(2) { said() }

        assertEquals(Delivery.Delivered, endpoint.publish(ExternalMessage("/lamp/watts", "30")))
        assertEquals(Delivery.Delivered, endpoint.publish(ExternalMessage("/lamp/state", """["on"]""")))

        assertEquals("""{"op":"publish","topic":"/lamp/watts","msg":{"data":30}}""", said())
        assertEquals("""{"op":"publish","topic":"/lamp/state","msg":{"data":"[\"on\"]"}}""", said())
    }

    @Test
    fun `a server that is not there makes the endpoint fail to open with the reason`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val endpoint = RosEndpoint(RosSettings(url = "ws://127.0.0.1:$closedPort", timeoutMs = 3_000))

        val failed = assertIs<Delivery.Failed>(endpoint.open(setOf("/lamp/press")))

        assertTrue(failed.reason.startsWith("The rosbridge server at ws://127.0.0.1:$closedPort "), failed.reason)
        assertEquals(Delivery.Failed("The ROS bridge is not connected."), endpoint.publish(ExternalMessage("/x", "1")))
    }

    @Test
    fun `after the server hung up the endpoint connects again and subscribes anew`() = runBlocking {
        val endpoint = endpoint(subscriptions = mapOf("/lamp/press" to null))
        endpoint.open(setOf("/lamp/press"))
        assertEquals("""{"op":"subscribe","topic":"/lamp/press"}""", said())

        rosbridge.hangUp()

        assertEquals("""{"op":"subscribe","topic":"/lamp/press"}""", said(RECONNECT_WAIT), "the endpoint subscribed again")
    }

    @Test
    fun `a bridge file runs end to end, a message becomes an event and a change of the model a message`() = runBlocking {
        val text = """
            {
              "kind": "ros",
              "settings": { "url": "${rosbridge.url}" },
              "inbound": [ { "from": "/lamp/dim", "event": "switch/dim", "payload": { "bright": "${'$'}.data" }, "type": "std_msgs/msg/Bool" } ],
              "outbound": [ { "property": "bulb/watts", "to": "/lamp/watts", "type": "std_msgs/msg/Float64" } ]
            }
        """.trimIndent()
        val config = checkNotNull(BridgeConfigFile.read(text).config)
        val link = RecordingLink()
        val bridge: Bridge = RosBridge(config, link, scope)

        bridge.start()
        assertEquals(BridgeState.RUNNING, bridge.status.value.state)
        repeat(2) { said() }

        rosbridge.say("""{"op": "publish", "topic": "/lamp/dim", "msg": {"data": true}}""")
        assertEquals(EventFrame("switch", "dim", EventPayload.Json("""{"bright":true}""")), link.sent.poll(WAIT, TimeUnit.MILLISECONDS))

        link.report(RuntimePush.ObjectChanged("bulb", 7, JsonText.read("""{"watts": 30}""") as WireValue.WireObject))
        assertEquals("""{"op":"publish","topic":"/lamp/watts","msg":{"data":30}}""", said())

        bridge.stop()
        assertEquals(BridgeState.STOPPED, bridge.status.value.state)
    }

    /** An endpoint on the server of the test, closed after the test. */
    private fun endpoint(
        ros1: Boolean = false,
        subscriptions: Map<String, String?> = emptyMap(),
        advertisements: Map<String, String?> = emptyMap(),
    ): RosEndpoint = RosEndpoint(RosSettings(url = rosbridge.url, ros1 = ros1), subscriptions, advertisements).also(endpoints::add)

    /** The next thing a client said to the server, waiting at most [within] milliseconds for it. */
    private fun said(within: Long = WAIT): String = checkNotNull(rosbridge.received.poll(within, TimeUnit.MILLISECONDS)) { "the bridge said nothing" }

    /** A model link that keeps the events it is sent and reports what a test tells it to. */
    private class RecordingLink : ModelLink {
        val sent = LinkedBlockingQueue<EventFrame>()
        private val reports = MutableSharedFlow<RuntimePush>(extraBufferCapacity = 16)

        override val pushes: Flow<RuntimePush> get() = reports

        override suspend fun send(event: EventFrame): Delivery {
            sent += event
            return Delivery.Delivered
        }

        suspend fun report(push: RuntimePush) = reports.emit(push)
    }

    private companion object {
        /** How long a test waits for something to arrive, in milliseconds. */
        const val WAIT = 5_000L

        /** How long a test waits for the endpoint to connect again, in milliseconds. */
        const val RECONNECT_WAIT = 15_000L
    }
}
