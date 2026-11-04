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
package io.karpfen.ide.runtime.protocol.golden

import io.karpfen.ide.runtime.protocol.catalogue.AccessKey
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.message.DecodedFrame
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.EventFrameEncoder
import io.karpfen.ide.runtime.protocol.message.EventPayload
import io.karpfen.ide.runtime.protocol.message.JsonReading
import io.karpfen.ide.runtime.protocol.message.PublishedEvent
import io.karpfen.ide.runtime.protocol.message.RuntimeFrameDecoder
import io.karpfen.ide.runtime.protocol.message.RuntimeJson
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.message.SnapshotReader
import io.karpfen.ide.runtime.protocol.message.SnapshotReading
import io.karpfen.ide.runtime.protocol.message.WireValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Covers the client's reading and writing against the runtime goldens: every push golden decodes without a violation to
 * the push it shows, its members read by name in whatever order the runtime writes them; every golden is the runtime's
 * JSON for the client's reader, the non-finite numbers included; the lamp's snapshot golden reads as the activated lamp
 * at revision 0; the event frame goldens are exactly what the encoder writes; and the handshake golden holds keys of the
 * client's shapes.
 */
class RuntimeGoldensTest {

    private val decoder = RuntimeFrameDecoder(EnvironmentKey(RuntimeGoldens.ENVIRONMENT), ClientId(RuntimeGoldens.CLIENT))

    @Test
    fun `every push golden decodes without a violation`() {
        RuntimeGoldens.PUSHES.forEach { frame ->
            val decoded = assertIs<DecodedFrame.Push>(decoder.decode(frame), frame)
            assertEquals(emptyList(), decoded.violations, frame)
        }
    }

    @Test
    fun `the four push examples decode to the pushes they show`() {
        assertEquals(
            listOf(
                RuntimePush.ObjectChanged("turtlePosition", 58, read(RuntimeGoldens.TURTLE_POSITION)),
                RuntimePush.ObjectDeleted("gen_Drone_4", 59),
                RuntimePush.StateChanged("turtle", listOf("observe"), 60),
                RuntimePush.DomainEvent(
                    "public",
                    PublishedEvent("start", "", "none", "EXTERNAL_MESSAGE", 1_761_560_130_123, 3000),
                    RuntimeGoldens.PUBLISHED_EVENT,
                ),
            ),
            listOf(RuntimeGoldens.OBJECT_CHANGED, RuntimeGoldens.OBJECT_DELETED, RuntimeGoldens.STATE_CHANGE, RuntimeGoldens.DOMAIN_EVENT)
                .map { push(it) },
        )
    }

    @Test
    fun `members in another order than org json writes them decode alike`() {
        assertEquals(RuntimePush.StateChanged("lamp", listOf("on", "full power"), 65), push(RuntimeGoldens.STATE_CHANGE_REORDERED))
        val domainEvent = assertIs<RuntimePush.DomainEvent>(push(RuntimeGoldens.DOMAIN_EVENT_REORDERED))
        assertEquals(PublishedEvent("press", "", "none", "EXTERNAL_MESSAGE", 1_761_560_130_123, 30_000), domainEvent.event)
    }

    @Test
    fun `the edge case goldens decode with their escapes, non-finite numbers, nulls and anonymous objects`() {
        val note = assertIs<RuntimePush.ObjectChanged>(push(RuntimeGoldens.ESCAPES_CHANGED)).value
        val sensor = assertIs<RuntimePush.ObjectChanged>(push(RuntimeGoldens.NON_FINITE_CHANGED)).value
        val robot = assertIs<RuntimePush.ObjectChanged>(push(RuntimeGoldens.RELATIONS_CHANGED)).value
        val turtle = assertIs<RuntimePush.ObjectChanged>(push(RuntimeGoldens.TURTLE_CHANGED)).value

        assertEquals(WireValue.WireString("line one\nback\\slash \"quoted\" \u0001 end"), note["text"])
        assertEquals(WireValue.WireNumber(Double.NaN), sensor["nan"])
        assertEquals(
            WireValue.WireArray(listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.0E20).map { WireValue.WireNumber(it) }),
            sensor["readings"],
        )
        assertEquals(WireValue.WireNull, robot["battery"])
        assertEquals(WireValue.WireString(""), assertIs<WireValue.WireObject>(robot["direction"])[WireNames.OBJECT_ID_MEMBER])
        assertEquals(WireValue.WireNull, assertIs<WireValue.WireArray>(robot["wheels"]).items.last())
        assertEquals(turtle["closest_obstacle"], assertIs<WireValue.WireArray>(turtle["obstacles"]).items.first(), "chair inlined twice")
    }

    @Test
    fun `the lamp's snapshot golden reads as the activated lamp at revision zero`() {
        val snapshot = assertIs<SnapshotReading.Read>(SnapshotReader.read(RuntimeGoldens.LAMP_SNAPSHOT_BEFORE_START)).snapshot

        assertEquals(EnvironmentKey(RuntimeGoldens.ENVIRONMENT), snapshot.environment)
        assertEquals(RuntimePhase.ACTIVATED, snapshot.phase)
        assertEquals(0, snapshot.revision)
        assertEquals(emptyMap(), snapshot.activeStates)
        assertEquals(listOf(read(RuntimeGoldens.LAMP_ROOT)), snapshot.roots)
    }

    @Test
    fun `the event frame goldens are what the encoder writes`() {
        val delivery = """{"packageId":"PKG-42","priority":2,"destination":{"x":12.0,"y":8.0}}"""

        assertEquals(RuntimeGoldens.START_EVENT_FRAME, EventFrameEncoder.encode(EventFrame("public", "start", EventPayload.None)))
        assertEquals(
            RuntimeGoldens.ASSIGN_DELIVERY_EVENT_FRAME,
            EventFrameEncoder.encode(EventFrame("control", "assignDelivery", EventPayload.Json(delivery))),
        )
    }

    @Test
    fun `the handshake golden joins a client id, an environment key and an access key of the client's shapes`() {
        val (client, environment, key) = RuntimeGoldens.HANDSHAKE.split(WireNames.HANDSHAKE_SEPARATOR)

        assertEquals(listOf("ide-4f2c", "env-1761555600000", "ak-ide-4f2c-1761555601234-4821"), listOf(client, environment, key))
        ClientId(client)
        EnvironmentKey(environment)
        AccessKey(key)
    }

    /** The push [frame] decodes to. */
    private fun push(frame: String): RuntimePush = assertIs<DecodedFrame.Push>(decoder.decode(frame), frame).push

    /** The JSON object [text]. */
    private fun read(text: String): WireValue.WireObject =
        assertIs<WireValue.WireObject>(assertIs<JsonReading.Read>(RuntimeJson.read(text), text).value)
}
