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
package io.karpfen.ide.ui.workbench.bridges

import com.sun.net.httpserver.HttpServer
import io.karpfen.ide.bridges.api.BridgeState
import io.karpfen.ide.bridges.api.Delivery
import io.karpfen.ide.bridges.api.ExternalMessage
import io.karpfen.ide.bridges.mqtt.MqttEndpoint
import io.karpfen.ide.bridges.mqtt.MqttSettings
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.conformance.DescriptorTiming
import io.karpfen.ide.runtime.protocol.conformance.LiveRuntime
import io.karpfen.ide.runtime.protocol.conformance.LiveRuntimeUnderTest
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.protocol.conformance.awaitValue
import io.karpfen.ide.runtime.protocol.conformance.eventually
import io.karpfen.ide.runtime.session.Preparation
import io.karpfen.ide.runtime.session.RuntimeSession
import io.karpfen.ide.runtime.session.RuntimeSessionOptions
import io.karpfen.ide.runtime.session.RuntimeSessions
import io.karpfen.ide.runtime.session.SessionState
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import io.moquette.broker.Server
import io.moquette.broker.config.MemoryConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Runs an HTTP bridge and an MQTT bridge of one run against the real runtime that the live task is given, usually a
 * throwaway container of `runtime/live-runtime.sh test`. It stands in for trying the two bridges by hand: the session
 * is a real one that deploys the lamp, the HTTP bridge listens on a port of this machine, and the MQTT bridge talks to
 * a broker that the test starts inside its own process.
 *
 * A device that listens on the broker and a small server that takes the posts of the HTTP bridge see what the model
 * does. The wall switch is pressed once through each bridge, and both times the change of the model has to come back
 * out through both bridges.
 */
@LiveRuntime
class LiveRunBridgesTest {

    @TempDir
    lateinit var dir: Path

    private val runtime = LiveRuntimeUnderTest()

    @Test
    fun `an HTTP bridge and an MQTT bridge of one run move the lamp and report what it does`() = runtime.run {
        val descriptor = SynthesizedModels.copyTo(dir.resolve("lamp"), SynthesizedModel.LAMP, DescriptorTiming.EVENT_SCENARIOS)
            .toAbsolutePath().normalize()
        val root = descriptor.parent
        val brokerPort = freePort()
        val httpPort = freePort()
        val broker = Server()
        val posted = CopyOnWriteArrayList<String>()
        val sink = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/position") { exchange ->
                posted += exchange.requestBody.readBytes().decodeToString()
                exchange.sendResponseHeaders(NO_CONTENT, -1)
                exchange.close()
            }
            start()
        }
        val http = Files.writeString(
            root.resolve("http.kbridge.json"),
            """
            {
              "kind": "http",
              "settings": { "port": "$httpPort" },
              "inbound": [ { "from": "/press", "event": "switch/press" } ],
              "outbound": [ { "property": "wallSwitch/position", "to": "http://127.0.0.1:${sink.address.port}/position" } ]
            }
            """.trimIndent(),
        )
        val mqtt = Files.writeString(
            root.resolve("mqtt.kbridge.json"),
            """
            {
              "kind": "mqtt",
              "settings": { "broker": "tcp://127.0.0.1:$brokerPort" },
              "inbound": [ { "from": "lamp/press", "event": "switch/press" } ],
              "outbound": [
                { "property": "wallSwitch/position", "to": "$POSITION" },
                { "state": "lamp", "to": "$STATE" }
              ]
            }
            """.trimIndent(),
        )
        val device = MqttEndpoint(
            MqttSettings.of(mapOf(MqttSettings.BROKER to "tcp://127.0.0.1:$brokerPort", MqttSettings.CLIENT_ID to "device")),
        )
        val heard = CopyOnWriteArrayList<ExternalMessage>()
        val workspace = Workspace(root, this, WorkspaceOptions(watch = false))
        var session: RuntimeSession? = null
        var bridges: RunBridges? = null
        val listening = launch(start = CoroutineStart.UNDISPATCHED) { device.incoming.collect { heard += it } }
        try {
            broker.startServer(MemoryConfig(brokerProperties(brokerPort)))
            assertEquals(Delivery.Delivered, device.open(setOf(POSITION, STATE)), diagnostics)

            val sessions = RuntimeSessions(
                RuntimeSessionOptions(dispatcher = clientOptions.dispatcher, timeSource = clientOptions.timeSource, random = Random.Default),
            )
            val prepared = assertIs<Preparation.Ready>(sessions.prepare(workspace, descriptor), diagnostics).prepared
            val launched = sessions.launch(
                prepared,
                RuntimeClient(runtime.endpoint, clientOptions, transports),
                this,
                DeployOptions(start = true, observe = true),
            )
            session = launched
            val loader = BridgeLoader(Dispatchers.IO)
            val started = RunBridges(listOf(loader.plan(root, http), loader.plan(root, mqtt)), launched, this, loader)
            bridges = started

            val state = awaitValue("the end of the deployment", launched.state, patience.deploy) { it != SessionState.Deploying }
            assertIs<SessionState.Running>(state, diagnostics)
            awaitValue("both bridges running", started.runs) { runs ->
                runs.none { it.status.state == BridgeState.STARTING || it.status.state == BridgeState.STOPPED }
            }
            assertEquals(
                listOf("http.kbridge.json" to BridgeState.RUNNING, "mqtt.kbridge.json" to BridgeState.RUNNING),
                started.runs.value.map { it.name to it.status.state },
                "${started.runs.value} $diagnostics",
            )

            eventually("the first report of the switch and of the lamp") {
                Unit.takeIf { last(heard, POSITION) == "\"down\"" && last(heard, STATE) == "[\"off\"]" && posted.lastOrNull() == "\"down\"" }
            }

            assertEquals(ACCEPTED, post(httpPort, "/press"), diagnostics)
            eventually("the lamp on after the press over HTTP, said over MQTT and over HTTP") {
                Unit.takeIf { last(heard, POSITION) == "\"up\"" && last(heard, STATE) == "[\"on\"]" && posted.lastOrNull() == "\"up\"" }
            }

            assertEquals(Delivery.Delivered, device.publish(ExternalMessage("lamp/press", "")), diagnostics)
            eventually("the lamp off after the press over MQTT, said over MQTT and over HTTP") {
                Unit.takeIf { last(heard, POSITION) == "\"down\"" && last(heard, STATE) == "[\"off\"]" && posted.lastOrNull() == "\"down\"" }
            }

            val (overHttp, overMqtt) = started.runs.value
            assertEquals(1, overHttp.status.inbound.single().delivered, "$overHttp")
            assertEquals(1, overMqtt.status.inbound.single().delivered, "$overMqtt")
            assertEquals(0, (overHttp.status.outbound + overMqtt.status.outbound).sumOf { it.failed }, "${started.runs.value}")
        } finally {
            withContext(NonCancellable) {
                listening.cancel()
                bridges?.close()
                session?.shutdown()
                device.close()
                workspace.close()
                broker.stopServer()
                sink.stop(0)
            }
        }
    }

    /** The body of the last message in [messages] that arrived for [topic], or null if there is none. */
    private fun last(messages: List<ExternalMessage>, topic: String): String? = messages.lastOrNull { it.address == topic }?.body

    /** Posts an empty request to [path] on [port] of this machine and returns the status of the answer. */
    private fun post(port: Int, path: String): Int {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).POST(HttpRequest.BodyPublishers.noBody()).build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    /** The settings of a broker on [port] of this machine that takes every client and keeps nothing on disk. */
    private fun brokerProperties(port: Int): Properties = Properties().apply {
        setProperty("host", "127.0.0.1")
        setProperty("port", port.toString())
        setProperty("allow_anonymous", "true")
        setProperty("persistence_enabled", "false")
        setProperty("data_path", dir.toString())
    }

    /** A port of this machine that nothing listens on right now. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        /** The topic on which the MQTT bridge says the position of the wall switch. */
        const val POSITION = "lamp/switch/position"

        /** The topic on which the MQTT bridge says the active states of the lamp. */
        const val STATE = "lamp/state"

        /** The status of an accepted request. */
        const val ACCEPTED = 202

        /** The status of an answer without a body. */
        const val NO_CONTENT = 204
    }
}
