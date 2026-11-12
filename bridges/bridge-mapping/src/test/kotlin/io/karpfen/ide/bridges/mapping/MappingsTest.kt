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

import io.karpfen.ide.bridges.api.ExternalMessage
import io.karpfen.ide.bridges.api.InboundRoute
import io.karpfen.ide.bridges.api.JsonPath
import io.karpfen.ide.bridges.api.JsonText
import io.karpfen.ide.bridges.api.OutboundRoute
import io.karpfen.ide.bridges.api.OutboundSource
import io.karpfen.ide.bridges.api.PayloadField
import io.karpfen.ide.bridges.api.ValueSource
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.EventPayload
import io.karpfen.ide.runtime.protocol.message.PublishedEvent
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.message.WireValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tests the two translations of a bridge: a message into an event, and a report of the model into a message. */
class MappingsTest {

    @Test
    fun `without a payload mapping the body is handed on if it is a JSON object`() {
        val route = InboundRoute("/dimmer", "switch", "dim")

        assertEquals(mapped("switch", "dim", EventPayload.Json("""{"bright": true}""")), Mappings.toEvent(route, message("""{"bright": true}""")))
        assertEquals(mapped("switch", "dim", EventPayload.None), Mappings.toEvent(route, message("")))
        assertEquals(mapped("switch", "dim", EventPayload.None), Mappings.toEvent(route, message("on")))
        assertEquals(mapped("switch", "dim", EventPayload.None), Mappings.toEvent(route, message("[1]")))
    }

    @Test
    fun `a payload mapping builds the payload from paths and fixed values`() {
        val route = InboundRoute(
            "/dimmer", "switch", "dim",
            payload = listOf(
                PayloadField("bright", ValueSource.FromMessage(path("$.direction.up"))),
                PayloadField("step", ValueSource.Fixed(WireValue.WireNumber(10.0))),
            ),
        )

        assertEquals(
            mapped("switch", "dim", EventPayload.Json("""{"bright":true,"step":10}""")),
            Mappings.toEvent(route, message("""{"direction": {"up": true}, "noise": 1}""")),
        )
        assertEquals(mapped("switch", "press", EventPayload.None), Mappings.toEvent(InboundRoute("/p", "switch", "press", payload = emptyList()), message("{}")))
    }

    @Test
    fun `a message that lacks what the mapping needs is rejected with the reason`() {
        val route = InboundRoute("/dimmer", "switch", "dim", payload = listOf(PayloadField("bright", ValueSource.FromMessage(path("$.up")))))

        assertEquals(InboundMapping.Rejected("The message is not JSON."), Mappings.toEvent(route, message("up")))
        assertEquals(InboundMapping.Rejected("The message has nothing at $.up for \"bright\"."), Mappings.toEvent(route, message("""{"down": true}""")))
    }

    @Test
    fun `a property route answers a change of its object with the value of its property`() {
        val route = OutboundRoute(OutboundSource.Property("bulb", "watts"), "home/lamp/watts")

        assertEquals(ExternalMessage("home/lamp/watts", "30"), Mappings.toMessage(route, changed("bulb", """{"watts": 30, "lit": true}""")))
        assertNull(Mappings.toMessage(route, changed("spare", """{"watts": 60}""")), "another object")
        assertNull(Mappings.toMessage(route, changed("bulb", """{"lit": true}""")), "the property is not there")
        assertNull(Mappings.toMessage(route, RuntimePush.StateChanged("bulb", listOf("on"), 1)))
    }

    @Test
    fun `a state route answers a change of its machine with the active states`() {
        val route = OutboundRoute(OutboundSource.State("lamp"), "home/lamp/state")

        assertEquals(
            ExternalMessage("home/lamp/state", """["on","full"]"""),
            Mappings.toMessage(route, RuntimePush.StateChanged("lamp", listOf("on", "full"), 3)),
        )
        assertNull(Mappings.toMessage(route, RuntimePush.StateChanged("wallSwitch", listOf("up"), 3)))
    }

    @Test
    fun `an event route answers its event with the payload`() {
        val route = OutboundRoute(OutboundSource.Event("lamp", "alarm"), "home/alarm")

        assertEquals(ExternalMessage("home/alarm", """{"level": 2}"""), Mappings.toMessage(route, event("lamp", "alarm", """{"level": 2}""")))
        assertEquals(ExternalMessage("home/alarm", "{}"), Mappings.toMessage(route, event("lamp", "alarm", "")))
        assertNull(Mappings.toMessage(route, event("lamp", "other", "{}")))
        assertNull(Mappings.toMessage(route, event("house", "alarm", "{}")))
    }

    @Test
    fun `routes have labels for a status display`() {
        assertEquals("/dimmer to switch/dim", Mappings.label(InboundRoute("/dimmer", "switch", "dim")))
        assertEquals("bulb/watts to t", Mappings.label(OutboundRoute(OutboundSource.Property("bulb", "watts"), "t")))
        assertEquals("state of lamp to t", Mappings.label(OutboundRoute(OutboundSource.State("lamp"), "t")))
        assertEquals("lamp/alarm to t", Mappings.label(OutboundRoute(OutboundSource.Event("lamp", "alarm"), "t")))
    }

    private fun message(body: String) = ExternalMessage("/dimmer", body)

    private fun mapped(domain: String, name: String, payload: EventPayload) = InboundMapping.Mapped(EventFrame(domain, name, payload))

    private fun path(text: String): JsonPath = checkNotNull(JsonPath.parse(text))

    private fun changed(objectId: String, json: String) =
        RuntimePush.ObjectChanged(objectId, 1, checkNotNull(JsonText.read(json)) as WireValue.WireObject)

    private fun event(domain: String, name: String, payload: String) =
        RuntimePush.DomainEvent(domain, PublishedEvent(name, payload, "json", "INTERNAL", 0, 1000), payload)
}
