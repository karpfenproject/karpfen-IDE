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

import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests how the settings of a ROS bridge are read from a bridge file and how its message types are written. */
class RosSettingsTest {

    @Test
    fun `without values the bridge talks to ROS 2 on the usual port of rosbridge`() {
        assertEquals(RosSettings("ws://localhost:9090", ros1 = false, timeoutMs = 5_000), RosSettings.of(emptyMap()))
    }

    @Test
    fun `the values of a bridge file are taken`() {
        val settings = RosSettings.of(mapOf("url" to " wss://robot.local:9443 ", "ros1" to "TRUE", "timeoutMs" to "800"))

        assertEquals(RosSettings("wss://robot.local:9443", ros1 = true, timeoutMs = 800), settings)
        assertEquals(emptyList(), RosSettings.problemsOf(mapOf("url" to "ws://robot:9090", "ros1" to "false", "timeoutMs" to "800")))
    }

    @Test
    fun `a value that makes no sense is named and replaced by its default`() {
        val values = mapOf("url" to "http://robot:9090", "ros1" to "yes", "timeoutMs" to "soon")

        assertEquals(RosSettings(), RosSettings.of(values))
        assertEquals(
            listOf(
                "The address \"http://robot:9090\" is no address of a rosbridge server such as ws://localhost:9090.",
                "\"ros1\" must be true or false, but is \"yes\".",
                "The timeout \"soon\" is no positive number of milliseconds.",
            ),
            RosSettings.problemsOf(values),
        )
    }

    @Test
    fun `a type is written with its category for ROS 2 and without it for ROS 1`() {
        assertEquals("std_msgs/msg/Float64", RosTypes.written("std_msgs/Float64", ros1 = false))
        assertEquals("std_msgs/msg/Float64", RosTypes.written(" std_msgs/msg/Float64 ", ros1 = false))
        assertEquals("std_msgs/Float64", RosTypes.written("std_msgs/msg/Float64", ros1 = true))
        assertEquals("std_msgs/Float64", RosTypes.written("std_msgs/Float64", ros1 = true))
        assertEquals("odd", RosTypes.written("odd", ros1 = false), "what is no type is left as it is")
    }

    @Test
    fun `a value of the model becomes a message of the type of its topic`() {
        assertEquals("""{"data":30}""", written("30", "std_msgs/msg/Float64"))
        assertEquals("""{"data":true}""", written("true", "std_msgs/msg/Bool"))
        assertEquals("""{"data":"on"}""", written("\"on\"", "std_msgs/msg/String"))
        assertEquals("""{"data":"30"}""", written("30", "std_msgs/msg/String"), "a text type takes the value as text")
        assertEquals("""{"data":"[\"on\",\"dimmed\"]"}""", written("""["on","dimmed"]""", "std_msgs/String"))
        assertEquals("""{"x":1,"y":2}""", written("""{"x": 1, "y": 2}""", "geometry_msgs/msg/Point"), "an object is the message itself")
        assertEquals("""{"data":"not json"}""", written("not json", "std_msgs/msg/String"))
    }

    private fun written(body: String, type: String): String = io.karpfen.ide.bridges.api.JsonText.write(RosTypes.messageOf(body, type))
}
