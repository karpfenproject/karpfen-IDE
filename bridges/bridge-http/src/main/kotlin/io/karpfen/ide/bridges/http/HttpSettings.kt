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
package io.karpfen.ide.bridges.http

/**
 * What the HTTP bridge needs besides its routes. A bridge file holds these values in its settings under the names
 * [HOST], [PORT] and [TIMEOUT_MS].
 *
 * @property host the address the bridge listens on. The default is the local machine only, so nothing outside can
 *   send events unless the file says otherwise.
 * @property port the port the bridge listens on. Port 0 lets the system pick a free one.
 * @property timeoutMs how long the bridge waits for the answer to a message it posts, in milliseconds.
 */
public data class HttpSettings(
    public val host: String = DEFAULT_HOST,
    public val port: Int = DEFAULT_PORT,
    public val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    public companion object {
        /** The name of the host setting. */
        public const val HOST: String = "host"

        /** The name of the port setting. */
        public const val PORT: String = "port"

        /** The name of the timeout setting. */
        public const val TIMEOUT_MS: String = "timeoutMs"

        /** The host the bridge listens on unless the file names another. */
        public const val DEFAULT_HOST: String = "127.0.0.1"

        /** The port the bridge listens on unless the file names another. */
        public const val DEFAULT_PORT: Int = 8090

        /** How long the bridge waits for an answer unless the file says otherwise. */
        public const val DEFAULT_TIMEOUT_MS: Long = 3_000

        private const val MAX_PORT = 65_535

        /** The settings that [values] hold. A value that is missing or makes no sense takes its default. */
        public fun of(values: Map<String, String>): HttpSettings = HttpSettings(
            host = values[HOST]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_HOST,
            port = values[PORT]?.trim()?.toIntOrNull()?.takeIf { it in 0..MAX_PORT } ?: DEFAULT_PORT,
            timeoutMs = values[TIMEOUT_MS]?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_MS,
        )

        /** What is wrong with [values], each in one sentence. [of] replaces these values by their defaults. */
        public fun problemsOf(values: Map<String, String>): List<String> = buildList {
            values[PORT]?.let { if (it.trim().toIntOrNull()?.takeIf { port -> port in 0..MAX_PORT } == null) add("The port \"$it\" is no number from 0 to $MAX_PORT.") }
            values[TIMEOUT_MS]?.let { if (it.trim().toLongOrNull()?.takeIf { timeout -> timeout > 0 } == null) add("The timeout \"$it\" is no positive number of milliseconds.") }
        }
    }
}
