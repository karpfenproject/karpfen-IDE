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
package io.karpfen.ide.runtime.protocol.message

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Covers [EventFrameEncoder]: the exact frames of an event without payload and of a JSON payload, the explicit KModel
 * format, the member order, the payload always written as a string, and the escaping of the runtime writer's rule.
 */
class EventFrameEncoderTest {

    @Test
    fun `an event without payload is written with an empty payload and the format none`() {
        assertEquals(
            """{"environmentKey":"public","messageType":"start","payload":"","payloadFormat":"none"}""",
            EventFrameEncoder.encode(EventFrame("public", "start", EventPayload.None)),
        )
    }

    @Test
    fun `a JSON payload is written as a string with the format json`() {
        val payload = """{"packageId":"PKG-42","priority":2,"destination":{"x":12.0,"y":8.0}}"""

        assertEquals(
            """{"environmentKey":"control","messageType":"assignDelivery",""" +
                """"payload":"{\"packageId\":\"PKG-42\",\"priority\":2,\"destination\":{\"x\":12.0,\"y\":8.0}}","payloadFormat":"json"}""",
            EventFrameEncoder.encode(EventFrame("control", "assignDelivery", EventPayload.Json(payload))),
        )
    }

    @Test
    fun `a KModel payload is written as a string with the format kmodel`() {
        val payload = "make object target : GeoPoint {\n\tx = 1\n}"

        val frame = EventFrameEncoder.encode(EventFrame("control", "teleport", EventPayload.Kmodel(payload)))

        val members = assertIs<WireValue.WireObject>(assertIs<JsonReading.Read>(RuntimeJson.read(frame)).value)
        assertEquals(listOf("environmentKey", "messageType", "payload", "payloadFormat"), members.fields.map { it.name })
        assertEquals(WireValue.WireString(payload), members["payload"])
        assertEquals(WireValue.WireString("kmodel"), members["payloadFormat"])
        assertEquals("""{"environmentKey":"control","messageType":"teleport","payload":"make object target : GeoPoint {\n\tx = 1\n}","payloadFormat":"kmodel"}""", frame)
    }

    @Test
    fun `quotes, backslashes and control characters are escaped in every member`() {
        val frame = EventFrameEncoder.encode(EventFrame("living room", "say \"hi\"", EventPayload.Json("{\"a\":\"b\\\\c\u0001\"}")))

        assertEquals(
            """{"environmentKey":"living room","messageType":"say \"hi\"","payload":"{\"a\":\"b\\\\c\u0001\"}","payloadFormat":"json"}""",
            frame,
        )
        val members = assertIs<WireValue.WireObject>(assertIs<JsonReading.Read>(RuntimeJson.read(frame)).value)
        assertEquals(WireValue.WireString("say \"hi\""), members["messageType"])
        assertEquals(WireValue.WireString("{\"a\":\"b\\\\c\u0001\"}"), members["payload"])
    }
}
