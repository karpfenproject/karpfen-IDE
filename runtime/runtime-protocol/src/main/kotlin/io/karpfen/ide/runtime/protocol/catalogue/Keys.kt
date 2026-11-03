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
package io.karpfen.ide.runtime.protocol.catalogue

import kotlin.random.Random

/**
 * The key of an environment on the runtime, such as `env-1761560123456` (the runtime hands out `env-<epoch millis>`,
 * each key once). The key travels in query parameters, in the WebSocket handshake `<clientId>:<envKey>:<accessKey>`
 * and in the envelope of every push, so it holds no `:` (the handshake is split at it), no `"` or `\` and no control
 * character, which keeps the envelope's strings plain.
 *
 * @property value the key as the runtime writes it.
 * @throws IllegalArgumentException if [value] is blank or holds one of the characters above.
 */
@JvmInline
public value class EnvironmentKey(public val value: String) {
    init {
        require(value.isNotBlank()) { "an environment key must not be blank" }
        require(value.none { it == ':' || it == '"' || it == '\\' || it.isISOControl() }) {
            "an environment key must not hold ':', '\"', '\\' or a control character: '$value'"
        }
    }

    override fun toString(): String = value
}

/**
 * The id under which a client registers with the runtime: its object, domain and model subscriptions and every access
 * key it is issued belong to it. One to 64 letters, digits, `_` and `-`, so it never holds the `:` of the handshake and
 * needs no escaping in the envelope; the IDE's client ids look like `karpfen-ide-4f2c9a1b7e03`.
 *
 * @property value the id as it travels on the wire.
 * @throws IllegalArgumentException if [value] does not follow the rule above.
 */
@JvmInline
public value class ClientId(public val value: String) {
    init {
        require(PATTERN.matches(value)) { "a client id is 1 to 64 letters, digits, '_' and '-', not '$value'" }
    }

    override fun toString(): String = value

    public companion object {
        /** The rule of a client id. */
        private val PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        /** The prefix of the client ids that [random] makes. */
        private const val IDE_PREFIX = "karpfen-ide-"

        /** The digits of the random part. */
        private const val HEX_DIGITS = "0123456789abcdef"

        /** The number of random hexadecimal digits. */
        private const val RANDOM_DIGITS = 12

        /**
         * A fresh client id of the IDE: `karpfen-ide-` followed by 12 lower-case hexadecimal digits drawn from [random],
         * so a seeded [Random] gives the same id every time.
         */
        public fun random(random: Random): ClientId =
            ClientId(IDE_PREFIX + String(CharArray(RANDOM_DIGITS) { HEX_DIGITS[random.nextInt(HEX_DIGITS.length)] }))
    }
}

/**
 * A single-use key that authenticates one WebSocket connection of a client to one environment, such as
 * `ak-karpfen-ide-4f2c9a1b7e03-1761555601234-4821`. It is the third part of the handshake, which is split at `:`, so it
 * holds no `:` and no whitespace (the runtime does not trim).
 *
 * @property value the key as the runtime issued it.
 * @throws IllegalArgumentException if [value] is blank or holds `:` or whitespace.
 */
@JvmInline
public value class AccessKey(public val value: String) {
    init {
        require(value.isNotBlank()) { "an access key must not be blank" }
        require(value.none { it == ':' || it.isWhitespace() }) { "an access key must not hold ':' or whitespace: '$value'" }
    }

    override fun toString(): String = value
}
