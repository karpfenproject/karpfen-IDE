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

import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests reading the settings of the MQTT bridge from a bridge file. */
class MqttSettingsTest {

    @Test
    fun `without values the bridge connects to a broker on the local machine`() {
        assertEquals(MqttSettings("localhost", 1883, tls = false, clientId = "karpfen-bridge", qos = 1, retain = false, timeoutMs = 5_000), MqttSettings.of(emptyMap()))
        assertEquals(emptyList(), MqttSettings.problemsOf(emptyMap()))
        assertEquals("localhost:1883", MqttSettings().address)
    }

    @Test
    fun `the broker is a URL, a host with a port, or a host alone`() {
        assertEquals("broker.local" to 1884, MqttSettings.of(mapOf("broker" to "tcp://broker.local:1884")).let { it.host to it.port })
        assertEquals("broker.local" to 1883, MqttSettings.of(mapOf("broker" to "mqtt://broker.local/")).let { it.host to it.port })
        assertEquals("10.0.0.5" to 1999, MqttSettings.of(mapOf("broker" to "10.0.0.5:1999")).let { it.host to it.port })
        assertEquals("box" to 1883, MqttSettings.of(mapOf("broker" to " box ")).let { it.host to it.port })
    }

    @Test
    fun `an encrypted broker uses its own default port`() {
        val encrypted = MqttSettings.of(mapOf("broker" to "ssl://broker.local"))

        assertEquals(Triple("broker.local", 8883, true), Triple(encrypted.host, encrypted.port, encrypted.tls))
        assertEquals(8884, MqttSettings.of(mapOf("broker" to "mqtts://broker.local:8884")).port)
    }

    @Test
    fun `the other values replace their defaults`() {
        val values = mapOf("clientId" to "lamp", "username" to "karl", "password" to "secret", "qos" to "2", "retain" to "TRUE", "timeoutMs" to "800")

        assertEquals(
            MqttSettings(clientId = "lamp", username = "karl", password = "secret", qos = 2, retain = true, timeoutMs = 800),
            MqttSettings.of(values),
        )
    }

    @Test
    fun `a value that makes no sense takes its default and is named`() {
        val values = mapOf("broker" to "http://broker local", "qos" to "3", "timeoutMs" to "0", "clientId" to " ")

        assertEquals(MqttSettings(), MqttSettings.of(values))
        assertEquals(
            listOf(
                "The broker \"http://broker local\" is no address such as tcp://localhost:1883.",
                "The quality of service \"3\" is not 0, 1 or 2.",
                "The timeout \"0\" is no positive number of milliseconds.",
            ),
            MqttSettings.problemsOf(values),
        )
    }

    @Test
    fun `the module id matches the module directory`() {
        assertEquals("bridge-mqtt", BridgeMqttModule.ID)
    }
}
