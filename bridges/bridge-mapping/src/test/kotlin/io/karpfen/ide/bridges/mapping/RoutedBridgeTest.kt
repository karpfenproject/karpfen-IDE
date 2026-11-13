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
package io.karpfen.ide.bridges.mapping

import io.karpfen.ide.bridges.api.BridgeConfig
import io.karpfen.ide.bridges.api.BridgeEndpoint
import io.karpfen.ide.bridges.api.BridgeKind
import io.karpfen.ide.bridges.api.BridgeState
import io.karpfen.ide.bridges.api.Delivery
import io.karpfen.ide.bridges.api.ExternalMessage
import io.karpfen.ide.bridges.api.FlowPolicy
import io.karpfen.ide.bridges.api.InboundRoute
import io.karpfen.ide.bridges.api.JsonText
import io.karpfen.ide.bridges.api.ModelLink
import io.karpfen.ide.bridges.api.OutboundRoute
import io.karpfen.ide.bridges.api.OutboundSource
import io.karpfen.ide.bridges.api.RouteStatus
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.EventPayload
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.message.WireValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests the bridge that runs the routes of a configuration, between an endpoint and a model link that live in memory.
 * The tests run on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutedBridgeTest {

    private val endpoint = FakeEndpoint()
    private val link = FakeLink()

    @Test
    fun `a started bridge listens at its inbound addresses and turns each message into its event`() = runTest {
        val bridge = bridge(inbound = listOf(InboundRoute("/press", "switch", "press"), InboundRoute("/dim", "switch", "dim")))

        bridge.start()
        runCurrent()
        endpoint.arrive("/dim", """{"bright": true}""")
        endpoint.arrive("/press", "")
        endpoint.arrive("/unknown", "")
        runCurrent()

        assertEquals(BridgeState.RUNNING, bridge.status.value.state)
        assertEquals(setOf("/press", "/dim"), endpoint.opened)
        assertEquals(
            listOf(EventFrame("switch", "dim", EventPayload.Json("""{"bright": true}""")), EventFrame("switch", "press", EventPayload.None)),
            link.sent,
        )
        assertEquals(
            listOf(RouteStatus("/press to switch/press", received = 1, delivered = 1), RouteStatus("/dim to switch/dim", received = 1, delivered = 1)),
            bridge.status.value.inbound,
        )
    }

    @Test
    fun `a bridge whose endpoint cannot open fails with the reason and can be started again`() = runTest {
        val bridge = bridge(inbound = listOf(InboundRoute("/press", "switch", "press")))
        endpoint.openOutcome = Delivery.Failed("Port 8090 is in use.")

        bridge.start()

        assertEquals(BridgeState.FAILED to "Port 8090 is in use.", bridge.status.value.let { it.state to it.problem })

        endpoint.openOutcome = Delivery.Delivered
        bridge.start()

        assertEquals(BridgeState.RUNNING to null, bridge.status.value.let { it.state to it.problem })
    }

    @Test
    fun `a message that cannot be mapped or sent counts as failed with the reason`() = runTest {
        val route = InboundRoute("/dim", "switch", "dim", payload = BridgeConfigFile.read(DIM_WITH_PAYLOAD).config!!.inbound.single().payload)
        val bridge = bridge(inbound = listOf(route))
        bridge.start()
        runCurrent()

        endpoint.arrive("/dim", "not json")
        runCurrent()
        link.sendOutcome = Delivery.Failed("The environment has stopped.")
        endpoint.arrive("/dim", """{"up": true}""")
        runCurrent()

        assertEquals(
            RouteStatus("/dim to switch/dim", received = 2, failed = 2, lastError = "The environment has stopped."),
            bridge.status.value.inbound.single(),
        )
    }

    @Test
    fun `a property route publishes a value only when it changed`() = runTest {
        val bridge = bridge(outbound = listOf(OutboundRoute(OutboundSource.Property("bulb", "watts"), "home/watts")))
        bridge.start()
        runCurrent()

        link.report(changed("bulb", """{"watts": 40, "lit": false}"""))
        link.report(changed("bulb", """{"watts": 40, "lit": true}"""))
        link.report(changed("spare", """{"watts": 60}"""))
        link.report(changed("bulb", """{"watts": 30, "lit": true}"""))
        runCurrent()

        assertEquals(listOf(ExternalMessage("home/watts", "40"), ExternalMessage("home/watts", "30")), endpoint.published)
        assertEquals(RouteStatus("bulb/watts to home/watts", received = 2, delivered = 2), bridge.status.value.outbound.single())
    }

    @Test
    fun `what the model reports while the endpoint opens goes out once it is open`() = runTest {
        val bridge = bridge(outbound = listOf(OutboundRoute(OutboundSource.Property("bulb", "watts"), "home/watts")))
        val opening = CompletableDeferred<Unit>().also { endpoint.opening = it }
        val starting = launch { bridge.start() }
        runCurrent()

        link.report(changed("bulb", """{"watts": 40}"""))
        runCurrent()
        assertEquals(emptyList(), endpoint.published)
        assertEquals(RouteStatus("bulb/watts to home/watts", received = 1), bridge.status.value.outbound.single())

        opening.complete(Unit)
        starting.join()
        runCurrent()

        assertEquals(listOf(ExternalMessage("home/watts", "40")), endpoint.published)
        assertEquals(RouteStatus("bulb/watts to home/watts", received = 1, delivered = 1), bridge.status.value.outbound.single())
    }

    @Test
    fun `an event route publishes every event, also the same one twice`() = runTest {
        val bridge = bridge(outbound = listOf(OutboundRoute(OutboundSource.Event("lamp", "alarm"), "home/alarm")))
        bridge.start()
        runCurrent()

        repeat(2) { link.report(RuntimePush.DomainEvent("lamp", published("alarm"), "{}")) }
        runCurrent()

        assertEquals(2, endpoint.published.size)
    }

    @Test
    fun `a queue keeps the newest messages up to its limit and counts what it drops`() = runTest {
        val bridge = bridge(inbound = listOf(InboundRoute("/n", "count", "n", flow = FlowPolicy.Queue(2))))
        bridge.start()
        runCurrent()
        link.gate = CompletableDeferred()

        (1..5).forEach { endpoint.arrive("/n", """{"n": $it}""") }
        runCurrent()
        link.gate!!.complete(Unit)
        runCurrent()

        assertEquals(listOf(1, 4, 5), link.sent.map(::numberOf), "the first one was under way, the queue kept the last two")
        assertEquals(RouteStatus("/n to count/n", received = 5, delivered = 3, dropped = 2), bridge.status.value.inbound.single())
    }

    @Test
    fun `latest keeps only the newest message`() = runTest {
        val bridge = bridge(inbound = listOf(InboundRoute("/n", "count", "n", flow = FlowPolicy.Latest)))
        bridge.start()
        runCurrent()
        link.gate = CompletableDeferred()

        (1..5).forEach { endpoint.arrive("/n", """{"n": $it}""") }
        runCurrent()
        link.gate!!.complete(Unit)
        runCurrent()

        assertEquals(listOf(1, 5), link.sent.map(::numberOf))
        assertEquals(3L, bridge.status.value.inbound.single().dropped)
    }

    @Test
    fun `sample hands on one message per period, always the newest`() = runTest {
        val bridge = bridge(inbound = listOf(InboundRoute("/n", "count", "n", flow = FlowPolicy.Sample(500))))
        bridge.start()
        runCurrent()

        (1..3).forEach { endpoint.arrive("/n", """{"n": $it}""") }
        runCurrent()
        assertEquals(listOf(1), link.sent.map(::numberOf), "the first one goes out at once")

        advanceTimeBy(499)
        runCurrent()
        assertEquals(listOf(1), link.sent.map(::numberOf), "the period is not over")

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(1, 3), link.sent.map(::numberOf))
        assertEquals(1L, bridge.status.value.inbound.single().dropped)
    }

    @Test
    fun `a stopped bridge closes its endpoint, hands nothing on and can be started again`() = runTest {
        val bridge = bridge(inbound = listOf(InboundRoute("/press", "switch", "press")))
        bridge.start()
        runCurrent()

        bridge.stop()
        endpoint.arrive("/press", "")
        runCurrent()

        assertEquals(BridgeState.STOPPED, bridge.status.value.state)
        assertEquals(1, endpoint.closes)
        assertEquals(emptyList(), link.sent)

        bridge.start()
        runCurrent()
        endpoint.arrive("/press", "")
        runCurrent()

        assertEquals(1, link.sent.size)
    }

    private fun TestScope.bridge(inbound: List<InboundRoute> = emptyList(), outbound: List<OutboundRoute> = emptyList()) =
        RoutedBridge(BridgeConfig(BridgeKind.HTTP, inbound = inbound, outbound = outbound), endpoint, link, backgroundScope)

    private fun numberOf(event: EventFrame): Int {
        val payload = JsonText.read((event.payload as EventPayload.Json).text) as WireValue.WireObject
        return (payload["n"] as WireValue.WireNumber).value.toInt()
    }

    private fun changed(objectId: String, json: String) =
        RuntimePush.ObjectChanged(objectId, 1, checkNotNull(JsonText.read(json)) as WireValue.WireObject)

    private fun published(name: String) = io.karpfen.ide.runtime.protocol.message.PublishedEvent(name, "{}", "json", "INTERNAL", 0, 1000)

    /**
     * An external system in memory. It records what a bridge publishes and lets a test make messages arrive. A test can
     * hold its opening back with [opening], and it takes nothing before it is open.
     */
    private class FakeEndpoint : BridgeEndpoint {
        private val arriving = MutableSharedFlow<ExternalMessage>(extraBufferCapacity = 64)
        val published = ArrayList<ExternalMessage>()
        var opened: Set<String> = emptySet()
        var openOutcome: Delivery = Delivery.Delivered
        var opening: CompletableDeferred<Unit>? = null
        var closes = 0
        private var isOpen = false

        override val incoming get() = arriving

        override suspend fun open(addresses: Set<String>): Delivery {
            opening?.await()
            opened = addresses
            isOpen = openOutcome == Delivery.Delivered
            return openOutcome
        }

        override suspend fun publish(message: ExternalMessage): Delivery {
            if (!isOpen) return Delivery.Failed("The endpoint is not open.")
            published += message
            return Delivery.Delivered
        }

        override suspend fun close() {
            closes++
        }

        fun arrive(address: String, body: String) {
            check(arriving.tryEmit(ExternalMessage(address, body)))
        }
    }

    /** A model in memory. It records the events a bridge sends, and a test can hold a send back with [gate]. */
    private class FakeLink : ModelLink {
        private val reported = MutableSharedFlow<RuntimePush>(extraBufferCapacity = 64)
        val sent = ArrayList<EventFrame>()
        var sendOutcome: Delivery = Delivery.Delivered
        var gate: CompletableDeferred<Unit>? = null

        override val pushes get() = reported

        override suspend fun send(event: EventFrame): Delivery {
            gate?.await()
            if (sendOutcome == Delivery.Delivered) sent += event
            return sendOutcome
        }

        fun report(push: RuntimePush) {
            check(reported.tryEmit(push))
        }
    }

    private companion object {
        val DIM_WITH_PAYLOAD = """{ "kind": "http", "inbound": [ { "from": "/dim", "event": "switch/dim", "payload": { "bright": "${'$'}.up" } } ] }"""
    }
}
