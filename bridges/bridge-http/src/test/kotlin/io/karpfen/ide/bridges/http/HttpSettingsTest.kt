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

import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests reading the settings of the HTTP bridge from a bridge file. */
class HttpSettingsTest {

    @Test
    fun `without values the bridge listens on the local machine at its default port`() {
        assertEquals(HttpSettings("127.0.0.1", 8090, 3_000), HttpSettings.of(emptyMap()))
        assertEquals(emptyList(), HttpSettings.problemsOf(emptyMap()))
    }

    @Test
    fun `values of the file replace the defaults`() {
        val values = mapOf("host" to "0.0.0.0", "port" to " 9100 ", "timeoutMs" to "500")

        assertEquals(HttpSettings("0.0.0.0", 9100, 500), HttpSettings.of(values))
        assertEquals(emptyList(), HttpSettings.problemsOf(values))
    }

    @Test
    fun `a value that makes no sense takes its default and is named`() {
        val values = mapOf("host" to " ", "port" to "70000", "timeoutMs" to "soon")

        assertEquals(HttpSettings(), HttpSettings.of(values))
        assertEquals(
            listOf("The port \"70000\" is no number from 0 to 65535.", "The timeout \"soon\" is no positive number of milliseconds."),
            HttpSettings.problemsOf(values),
        )
    }

    @Test
    fun `the module id matches the module directory`() {
        assertEquals("bridge-http", BridgeHttpModule.ID)
    }
}
