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
import io.karpfen.ide.bridges.api.BridgeKind
import io.karpfen.ide.bridges.api.FlowPolicy
import io.karpfen.ide.bridges.api.InboundRoute
import io.karpfen.ide.bridges.api.JsonPath
import io.karpfen.ide.bridges.api.OutboundRoute
import io.karpfen.ide.bridges.api.OutboundSource
import io.karpfen.ide.bridges.api.PayloadField
import io.karpfen.ide.bridges.api.ValueSource
import io.karpfen.ide.runtime.protocol.message.WireValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests reading and writing bridge files. */
class BridgeConfigFileTest {

    @Test
    fun `a route may name the type of its messages, and the type is written back`() {
        val text = """
            {
              "kind": "ros",
              "settings": { "url": "ws://localhost:9090" },
              "inbound": [ { "from": "/switch", "event": "switch/press", "type": "std_msgs/msg/Empty" }, { "from": "/dim", "event": "switch/dim" } ],
              "outbound": [ { "property": "bulb/watts", "to": "/lamp/watts", "type": "std_msgs/msg/Float64", "policy": "latest" } ]
            }
        """.trimIndent()

        val config = checkNotNull(BridgeConfigFile.read(text).config)

        assertEquals(listOf("std_msgs/msg/Empty", null), config.inbound.map { it.messageType })
        assertEquals("std_msgs/msg/Float64", config.outbound.single().messageType)
        assertEquals(config, BridgeConfigFile.read(BridgeConfigFile.write(config)).config)
    }

    private val lamp = BridgeConfig(
        kind = BridgeKind.HTTP,
        settings = mapOf("port" to "8090"),
        inbound = listOf(
            InboundRoute("/switch/press", "switch", "press"),
            InboundRoute(
                "/dimmer", "switch", "dim",
                payload = listOf(
                    PayloadField("bright", ValueSource.FromMessage(checkNotNull(JsonPath.parse("$.up")))),
                    PayloadField("step", ValueSource.Fixed(WireValue.WireNumber(10.0))),
                ),
                flow = FlowPolicy.Latest,
            ),
        ),
        outbound = listOf(
            OutboundRoute(OutboundSource.Property("bulb", "watts"), "http://localhost:9000/lamp/watts"),
            OutboundRoute(OutboundSource.State("lamp"), "http://localhost:9000/lamp/state", FlowPolicy.Sample(500)),
            OutboundRoute(OutboundSource.Event("lamp", "alarm"), "http://localhost:9000/alarm", FlowPolicy.Queue(8)),
        ),
    )

    @Test
    fun `a file holds the kind, the settings and the routes of both directions`() {
        val reading = BridgeConfigFile.read(
            """
            {
              "kind": "http",
              "settings": { "port": 8090 },
              "inbound": [
                { "from": "/switch/press", "event": "switch/press" },
                { "from": "/dimmer", "event": "switch/dim", "payload": { "bright": "${'$'}.up", "step": 10 }, "policy": "latest" }
              ],
              "outbound": [
                { "property": "bulb/watts", "to": "http://localhost:9000/lamp/watts" },
                { "state": "lamp", "to": "http://localhost:9000/lamp/state", "policy": "sample", "periodMs": 500 },
                { "event": "lamp/alarm", "to": "http://localhost:9000/alarm", "policy": "queue", "limit": 8 }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(emptyList(), reading.problems)
        assertEquals(lamp, reading.config)
    }

    @Test
    fun `what is written reads back to the same configuration`() {
        val text = BridgeConfigFile.write(lamp)

        assertEquals(BridgeConfigReading(lamp, emptyList()), BridgeConfigFile.read(text))
        assertTrue(text.startsWith("{\n  \"kind\": \"http\","), text)
        assertEquals(BridgeConfig(BridgeKind.MQTT), BridgeConfigFile.read(BridgeConfigFile.write(BridgeConfig(BridgeKind.MQTT))).config)
    }

    @Test
    fun `a file that is no object or names no known kind has no configuration`() {
        assertEquals(BridgeConfigReading(null, listOf("The file is not a JSON object.")), BridgeConfigFile.read("[]"))
        assertEquals(BridgeConfigReading(null, listOf("The file is not a JSON object.")), BridgeConfigFile.read("not json"))
        assertEquals(
            listOf("\"kind\" must be one of http, mqtt, ros, but is carrier-pigeon."),
            BridgeConfigFile.read("""{ "kind": "carrier-pigeon" }""").problems,
        )
        assertNull(BridgeConfigFile.read("{}").config)
    }

    @Test
    fun `a route with a problem is left out and named, and the others stay`() {
        val reading = BridgeConfigFile.read(
            """
            {
              "kind": "mqtt",
              "inbound": [
                { "from": "home/switch", "event": "switch/press" },
                { "event": "switch/press" },
                { "from": "home/dimmer", "event": "dim" },
                { "from": "home/dimmer", "event": "switch/dim", "payload": { "bright": "${'$'}..up" } },
                { "from": "home/dimmer", "event": "switch/dim", "policy": "sometimes" }
              ],
              "outbound": [
                { "property": "bulb/watts", "state": "lamp", "to": "home/lamp" },
                { "property": "bulb/watts" },
                { "state": "lamp", "to": "home/lamp/state", "policy": "sample" }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf(InboundRoute("home/switch", "switch", "press")), reading.config?.inbound)
        assertEquals(emptyList(), reading.config?.outbound)
        assertEquals(
            listOf(
                "inbound[1] needs \"from\".",
                "inbound[2] needs \"event\" as domain/name.",
                "inbound[3]: the payload field \"bright\" is no valid path.",
                "inbound[4]: the policy \"sometimes\" is not known. Use queue, latest or sample.",
                "outbound[0] needs exactly one of \"property\" as object/property, \"state\" or \"event\" as domain/name.",
                "outbound[1] needs \"to\".",
                "outbound[2]: the policy sample needs a positive \"periodMs\".",
            ),
            reading.problems,
        )
    }

    @Test
    fun `an object key with a slash keeps it, because the property is what follows the last slash`() {
        val reading = BridgeConfigFile.read("""{ "kind": "http", "outbound": [ { "property": "floor/1/bulb/watts", "to": "x" } ] }""")

        assertEquals(OutboundSource.Property("floor/1/bulb", "watts"), reading.config?.outbound?.single()?.source)
    }
}
