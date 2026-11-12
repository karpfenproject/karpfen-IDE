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
package io.karpfen.ide.bridges.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient
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
import io.moquette.broker.Server
import io.moquette.broker.config.MemoryConfig
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
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests the MQTT bridge against a real broker. The broker runs inside the test on a free port of the local machine,
 * and a second MQTT client stands in for the devices that publish and subscribe.
 */
class MqttBridgeTest {

    @TempDir
    lateinit var dir: Path

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val broker = Server()
    private val endpoints = ArrayList<MqttEndpoint>()
    private var port = 0
    private lateinit var device: Mqtt3BlockingClient

    @BeforeTest
    fun startBroker() {
        port = ServerSocket(0).use { it.localPort }
        val properties = Properties().apply {
            setProperty("host", "127.0.0.1")
            setProperty("port", port.toString())
            setProperty("allow_anonymous", "true")
            setProperty("persistence_enabled", "false")
            setProperty("data_path", dir.toString())
        }
        broker.startServer(MemoryConfig(properties))
        device = MqttClient.builder().useMqttVersion3().identifier("device").serverHost("127.0.0.1").serverPort(port).buildBlocking()
        device.connect()
    }

    @AfterTest
    fun stopBroker() = runBlocking {
        endpoints.forEach { it.close() }
        runCatching { device.disconnect() }
        broker.stopServer()
        scope.cancel()
    }

    @Test
    fun `a message on the topic of a route arrives with the address of that route`() = runBlocking {
        val endpoint = endpoint()
        assertEquals(Delivery.Delivered, endpoint.open(setOf("home/switch", "home/+/dimmer")))
        val arrived = Channel<ExternalMessage>(Channel.UNLIMITED)
        val reader = scope.launch(start = CoroutineStart.UNDISPATCHED) { endpoint.incoming.collect(arrived::send) }

        publish("home/switch", "pressed")
        publish("home/kitchen/dimmer", """{"up": true}""")
        publish("home/other", "ignored")

        assertEquals(ExternalMessage("home/switch", "pressed"), withTimeout(WAIT) { arrived.receive() })
        assertEquals(ExternalMessage("home/+/dimmer", """{"up": true}"""), withTimeout(WAIT) { arrived.receive() }, "the address is the filter of the route")
        reader.cancel()
    }

    @Test
    fun `an outbound message is published on its topic`() = runBlocking {
        val received = LinkedBlockingQueue<Pair<String, String>>()
        device.toAsync().subscribeWith().topicFilter("home/lamp/#").qos(MqttQos.AT_LEAST_ONCE)
            .callback { received += it.topic.toString() to it.payloadAsBytes.decodeToString() }
            .send().get(WAIT, TimeUnit.MILLISECONDS)
        val endpoint = endpoint()
        endpoint.open(emptySet())

        assertEquals(Delivery.Delivered, endpoint.publish(ExternalMessage("home/lamp/watts", "30")))

        assertEquals("home/lamp/watts" to "30", received.poll(WAIT, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a broker that is not there makes the endpoint fail to open with the reason`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val endpoint = MqttEndpoint(MqttSettings(host = "127.0.0.1", port = closedPort, timeoutMs = 3_000))

        val failed = assertIs<Delivery.Failed>(endpoint.open(setOf("home/switch")))

        assertTrue(failed.reason.startsWith("The MQTT broker at 127.0.0.1:$closedPort "), failed.reason)
        assertEquals(Delivery.Failed("The MQTT bridge is not connected."), endpoint.publish(ExternalMessage("home/x", "1")))
    }

    @Test
    fun `a topic that cannot be used is named`() = runBlocking {
        assertEquals(Delivery.Failed("\"home/#/dimmer\" is no topic that can be subscribed to."), endpoint().open(setOf("home/#/dimmer")))

        val endpoint = endpoint("second")
        endpoint.open(emptySet())
        assertEquals(Delivery.Failed("\"home/+/watts\" is no topic that can be published on."), endpoint.publish(ExternalMessage("home/+/watts", "1")))
    }

    @Test
    fun `a bridge file runs end to end, a message becomes an event and a change of the model a message`() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        device.toAsync().subscribeWith().topicFilter("home/lamp/watts").qos(MqttQos.AT_LEAST_ONCE)
            .callback { received += it.payloadAsBytes.decodeToString() }
            .send().get(WAIT, TimeUnit.MILLISECONDS)
        val text = """
            {
              "kind": "mqtt",
              "settings": { "broker": "tcp://127.0.0.1:$port", "clientId": "lamp-bridge" },
              "inbound": [ { "from": "home/dimmer", "event": "switch/dim", "payload": { "bright": "${'$'}.up" } } ],
              "outbound": [ { "property": "bulb/watts", "to": "home/lamp/watts" } ]
            }
        """.trimIndent()
        val config = checkNotNull(BridgeConfigFile.read(text).config)
        val link = RecordingLink()
        val endpoint = MqttEndpoint(MqttSettings.of(config.settings)).also(endpoints::add)
        val bridge: Bridge = RoutedBridge(config, endpoint, link, scope)

        bridge.start()
        assertEquals(BridgeState.RUNNING, bridge.status.value.state)

        publish("home/dimmer", """{"up": true}""")
        assertEquals(EventFrame("switch", "dim", EventPayload.Json("""{"bright":true}""")), link.sent.poll(WAIT, TimeUnit.MILLISECONDS))

        link.report(RuntimePush.ObjectChanged("bulb", 7, JsonText.read("""{"watts": 30}""") as WireValue.WireObject))
        assertEquals("30", received.poll(WAIT, TimeUnit.MILLISECONDS))

        bridge.stop()
        assertEquals(BridgeState.STOPPED, bridge.status.value.state)
    }

    @Test
    fun `the factory builds a bridge for an mqtt configuration only`() {
        val link = RecordingLink()

        assertNotNull(MqttBridge(BridgeConfig(BridgeKind.MQTT), link, scope))
        assertIs<IllegalArgumentException>(runCatching { MqttBridge(BridgeConfig(BridgeKind.HTTP), link, scope) }.exceptionOrNull())
    }

    /** An endpoint for the broker of the test. It is closed after the test. */
    private fun endpoint(clientId: String = "bridge"): MqttEndpoint =
        MqttEndpoint(MqttSettings(host = "127.0.0.1", port = port, clientId = clientId)).also(endpoints::add)

    private fun publish(topic: String, body: String) {
        device.publishWith().topic(topic).payload(body.encodeToByteArray()).qos(MqttQos.AT_LEAST_ONCE).send()
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
        const val WAIT = 10_000L
    }
}
