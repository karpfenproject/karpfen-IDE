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

import io.karpfen.ide.bridges.api.BridgeKind
import io.karpfen.ide.bridges.mapping.BridgeConfigFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests the draft of the bridge form: it shows a bridge file, turns back into the same file, and says in the words of
 * the form what keeps it from being saved.
 */
class BridgeDraftsTest {

    private val file = """
        {
          "kind": "mqtt",
          "settings": { "broker": "tcp://localhost:1883", "retain": "true" },
          "inbound": [
            { "from": "home/press", "event": "switch/press" },
            { "from": "home/dim", "event": "switch/dim",
              "payload": { "bright": "$.up", "step": 10, "room": "living room", "code": "42", "on": true },
              "policy": "latest" },
            { "from": "home/smash", "event": "service/smash", "payload": {}, "policy": "queue", "limit": 8 }
          ],
          "outbound": [
            { "property": "living room/lamp/watts", "to": "home/watts" },
            { "state": "lamp", "to": "home/state", "policy": "sample", "periodMs": 500 },
            { "event": "lamp/broke", "to": "home/broke" }
          ]
        }
    """.trimIndent()

    @Test
    fun `a draft shows a bridge file in the texts of the form`() {
        val draft = BridgeDrafts.of(assertNotNull(BridgeConfigFile.read(file).config))

        assertEquals(BridgeKind.MQTT, draft.kind)
        assertEquals(mapOf("broker" to "tcp://localhost:1883", "retain" to "true"), draft.settings)
        assertEquals(InboundDraft("home/press", "switch/press"), draft.inbound[0])
        assertEquals(
            InboundDraft(
                from = "home/dim",
                event = "switch/dim",
                passBody = false,
                fields = listOf(
                    FieldDraft("bright", "$.up"),
                    FieldDraft("step", "10"),
                    FieldDraft("room", "living room"),
                    FieldDraft("code", "\"42\""),
                    FieldDraft("on", "true"),
                ),
                policy = PolicyDraft(PolicyKind.LATEST),
            ),
            draft.inbound[1],
        )
        assertEquals(InboundDraft("home/smash", "service/smash", passBody = false, policy = PolicyDraft(PolicyKind.QUEUE, "8")), draft.inbound[2])
        assertEquals(
            listOf(
                OutboundDraft(Follows.PROPERTY, "living room/lamp/watts", "home/watts"),
                OutboundDraft(Follows.STATE, "lamp", "home/state", PolicyDraft(PolicyKind.SAMPLE, "500")),
                OutboundDraft(Follows.EVENT, "lamp/broke", "home/broke"),
            ),
            draft.outbound,
        )
    }

    @Test
    fun `a draft turns back into the configuration it was made of`() {
        val config = assertNotNull(BridgeConfigFile.read(file).config)

        assertEquals(config, BridgeDrafts.configOf(BridgeDrafts.of(config)))
        assertEquals(emptyList(), BridgeDrafts.problemsOf(BridgeDrafts.of(config)))
    }

    @Test
    fun `blank settings are left out and values are trimmed`() {
        val draft = BridgeDraft(
            settings = mapOf("host" to " ", "port" to " 9000 "),
            inbound = listOf(InboundDraft(" /press ", " switch/press ")),
        )

        val config = assertNotNull(BridgeDrafts.configOf(draft))

        assertEquals(mapOf("port" to "9000"), config.settings)
        assertEquals("/press", config.inbound.single().from)
        assertEquals("switch" to "press", config.inbound.single().let { it.domain to it.event })
    }

    @Test
    fun `what is wrong is said in the words of the form and keeps the draft from being a configuration`() {
        val draft = BridgeDraft(
            settings = mapOf("port" to "eighty"),
            inbound = listOf(
                InboundDraft("", "press"),
                InboundDraft("/dim", "switch/dim", passBody = false, fields = listOf(FieldDraft("", "$.up"), FieldDraft("a", "$[")), policy = PolicyDraft(PolicyKind.QUEUE, "0")),
                InboundDraft("/same", "switch/press"),
                InboundDraft("/same", "switch/press"),
            ),
            outbound = listOf(
                OutboundDraft(Follows.PROPERTY, "watts", ""),
                OutboundDraft(Follows.STATE, " ", "http://localhost/x", PolicyDraft(PolicyKind.SAMPLE, "")),
                OutboundDraft(Follows.EVENT, "broke", "http://localhost/y"),
            ),
        )

        val problems = BridgeDrafts.problemsOf(draft)

        assertNull(BridgeDrafts.configOf(draft))
        assertEquals(1, problems.count { "port" in it }, "$problems")
        assertEquals(
            listOf(
                "Inbound route 1 needs an address.",
                "Inbound route 1 needs an event as domain/name.",
                "Inbound route 2 has a payload field without a name.",
                "Inbound route 2: \"$[\" is no path. A path looks like $.bright or $.items[0].",
                "Inbound route 2: the length of the line must be a positive number.",
                "Outbound route 1 needs a property as object/property.",
                "Outbound route 1 needs a destination.",
                "Outbound route 2 needs the object its state machine is attached to.",
                "Outbound route 2: the period in ms must be a positive number.",
                "Outbound route 3 needs an event as domain/name.",
                "Several inbound routes listen at \"/same\". Each address can have one route only.",
            ),
            problems.filterNot { "port" in it },
        )
    }

    @Test
    fun `changing the kind keeps the routes and only the settings the new kind has as well`() {
        val draft = BridgeDraft(
            kind = BridgeKind.HTTP,
            settings = mapOf("port" to "9000", "timeoutMs" to "100"),
            inbound = listOf(InboundDraft("/press", "switch/press")),
        )

        val changed = BridgeDrafts.withKind(draft, BridgeKind.MQTT)

        assertEquals(BridgeKind.MQTT, changed.kind)
        assertEquals(mapOf("timeoutMs" to "100"), changed.settings)
        assertEquals(draft.inbound, changed.inbound)
    }
}
