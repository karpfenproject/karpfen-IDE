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

/**
 * How the ROS bridge reaches ROS. It talks to a rosbridge server, the package `rosbridge_suite` that is installed and
 * started on the ROS side. That server offers the topics of a ROS system as JSON over a WebSocket. The settings come
 * from the `settings` of a bridge file, whose keys are [URL], [ROS1] and [TIMEOUT_MS].
 *
 * @property url the WebSocket address of the rosbridge server.
 * @property ros1 true if the server belongs to a ROS 1 system. ROS 2 is the default. The two differ in how they write
 *   the types of messages, which [RosTypes] takes care of.
 * @property timeoutMs how long connecting to the server may take, in milliseconds.
 */
public data class RosSettings(
    public val url: String = DEFAULT_URL,
    public val ros1: Boolean = false,
    public val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    public companion object {
        /** The key of the address of the rosbridge server, such as `ws://localhost:9090`. */
        public const val URL: String = "url"

        /** The key of the switch for a ROS 1 system, `true` or `false`. */
        public const val ROS1: String = "ros1"

        /** The key of the time in milliseconds that connecting may take. */
        public const val TIMEOUT_MS: String = "timeoutMs"

        /** Where a rosbridge server listens unless it is told otherwise. */
        public const val DEFAULT_URL: String = "ws://localhost:9090"

        /** How long connecting may take unless the settings say otherwise. */
        public const val DEFAULT_TIMEOUT_MS: Long = 5_000

        /** What the address of a rosbridge server starts with: a plain or an encrypted WebSocket. */
        private val SCHEMES = listOf("ws://", "wss://")

        /** The settings that [values] describe. A value that makes no sense is replaced by its default. */
        public fun of(values: Map<String, String>): RosSettings = RosSettings(
            url = values[URL]?.trim()?.takeIf(::isAddress) ?: DEFAULT_URL,
            ros1 = values[ROS1]?.trim().equals("true", ignoreCase = true),
            timeoutMs = values[TIMEOUT_MS]?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_MS,
        )

        /** What is wrong with [values], each in one sentence. [of] replaces these values by their defaults. */
        public fun problemsOf(values: Map<String, String>): List<String> = buildList {
            values[URL]?.let { if (!isAddress(it.trim())) add("The address \"$it\" is no address of a rosbridge server such as $DEFAULT_URL.") }
            values[ROS1]?.let { if (it.trim().lowercase() !in setOf("true", "false")) add("\"$ROS1\" must be true or false, but is \"$it\".") }
            values[TIMEOUT_MS]?.let { if (it.trim().toLongOrNull()?.takeIf { timeout -> timeout > 0 } == null) add("The timeout \"$it\" is no positive number of milliseconds.") }
        }

        private fun isAddress(text: String): Boolean = SCHEMES.any { text.startsWith(it, ignoreCase = true) && text.length > it.length }
    }
}
