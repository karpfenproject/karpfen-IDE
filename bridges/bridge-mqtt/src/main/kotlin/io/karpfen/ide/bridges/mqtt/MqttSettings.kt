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

/**
 * What the MQTT bridge needs besides its routes. A bridge file holds these values in its settings under the names
 * [BROKER], [CLIENT_ID], [USERNAME], [PASSWORD], [QOS], [RETAIN] and [TIMEOUT_MS].
 *
 * @property host the host of the broker.
 * @property port the port of the broker.
 * @property tls true if the connection to the broker is encrypted.
 * @property clientId the name the bridge gives itself at the broker. A broker allows one connection per name.
 * @property username the user name for the broker, or null if the broker asks for none.
 * @property password the password for the broker, or null.
 * @property qos how sure a delivery is, for subscriptions and for published messages: 0 at most once, 1 at least
 *   once, 2 exactly once.
 * @property retain true if the broker keeps the last published message of a topic for those who subscribe later.
 * @property timeoutMs how long the bridge waits for the broker to answer, in milliseconds.
 */
public data class MqttSettings(
    public val host: String = DEFAULT_HOST,
    public val port: Int = DEFAULT_PORT,
    public val tls: Boolean = false,
    public val clientId: String = DEFAULT_CLIENT_ID,
    public val username: String? = null,
    public val password: String? = null,
    public val qos: Int = DEFAULT_QOS,
    public val retain: Boolean = false,
    public val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    /** The broker in a few words for a message to the user, such as `localhost:1883`. */
    public val address: String get() = "$host:$port"

    public companion object {
        /** The name of the broker setting. Its value is a URL such as `tcp://localhost:1883` or `ssl://host:8883`. */
        public const val BROKER: String = "broker"

        /** The name of the client id setting. */
        public const val CLIENT_ID: String = "clientId"

        /** The name of the user name setting. */
        public const val USERNAME: String = "username"

        /** The name of the password setting. */
        public const val PASSWORD: String = "password"

        /** The name of the quality of service setting. */
        public const val QOS: String = "qos"

        /** The name of the retain setting. */
        public const val RETAIN: String = "retain"

        /** The name of the timeout setting. */
        public const val TIMEOUT_MS: String = "timeoutMs"

        /** The host of the broker unless the file names another. */
        public const val DEFAULT_HOST: String = "localhost"

        /** The port of a broker without encryption. */
        public const val DEFAULT_PORT: Int = 1883

        /** The port of a broker with encryption. */
        public const val DEFAULT_TLS_PORT: Int = 8883

        /** The name of the bridge at the broker unless the file names another. */
        public const val DEFAULT_CLIENT_ID: String = "karpfen-bridge"

        /** The quality of service unless the file names another: at least once. */
        public const val DEFAULT_QOS: Int = 1

        /** How long the bridge waits for the broker unless the file says otherwise. */
        public const val DEFAULT_TIMEOUT_MS: Long = 5_000

        private const val MAX_PORT = 65_535
        private val BROKER_URL = Regex("""^(?:(tcp|mqtt|ssl|tls|mqtts)://)?([^:/\s]+)(?::(\d{1,5}))?/?$""", RegexOption.IGNORE_CASE)
        private val ENCRYPTED = setOf("ssl", "tls", "mqtts")

        /** The settings that [values] hold. A value that is missing or makes no sense takes its default. */
        public fun of(values: Map<String, String>): MqttSettings {
            val broker = values[BROKER]?.trim()?.let(BROKER_URL::matchEntire)
            val tls = broker?.groupValues?.get(1)?.lowercase() in ENCRYPTED
            return MqttSettings(
                host = broker?.groupValues?.get(2) ?: DEFAULT_HOST,
                port = broker?.groupValues?.get(3)?.toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: if (tls) DEFAULT_TLS_PORT else DEFAULT_PORT,
                tls = tls,
                clientId = values[CLIENT_ID]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_CLIENT_ID,
                username = values[USERNAME]?.takeIf { it.isNotEmpty() },
                password = values[PASSWORD]?.takeIf { it.isNotEmpty() },
                qos = values[QOS]?.trim()?.toIntOrNull()?.takeIf { it in 0..2 } ?: DEFAULT_QOS,
                retain = values[RETAIN]?.trim().equals("true", ignoreCase = true),
                timeoutMs = values[TIMEOUT_MS]?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_MS,
            )
        }

        /** What is wrong with [values], each in one sentence. [of] replaces these values by their defaults. */
        public fun problemsOf(values: Map<String, String>): List<String> = buildList {
            values[BROKER]?.let { if (BROKER_URL.matchEntire(it.trim()) == null) add("The broker \"$it\" is no address such as tcp://localhost:1883.") }
            values[QOS]?.let { if (it.trim().toIntOrNull()?.takeIf { qos -> qos in 0..2 } == null) add("The quality of service \"$it\" is not 0, 1 or 2.") }
            values[TIMEOUT_MS]?.let { if (it.trim().toLongOrNull()?.takeIf { timeout -> timeout > 0 } == null) add("The timeout \"$it\" is no positive number of milliseconds.") }
        }
    }
}
