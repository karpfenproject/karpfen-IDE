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
package io.karpfen.ide.runtime.protocol.transport

import io.karpfen.ide.runtime.protocol.catalogue.HttpVerb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers the values of the transport seams. A PUT always has a body, a GET never has one and a POST may have one, and
 * transports are released once.
 */
class TransportValuesTest {

    @Test
    fun `a PUT carries a body, a GET none, and a POST one only when it has a message`() {
        val upload = TextRequest(HttpVerb.PUT, "/setModel", listOf("envKey" to "env-1"), "make object \"a\":\"A\" { }")
        assertEquals("/setModel", upload.path)
        TextRequest(HttpVerb.GET, "/observatory/model", emptyList(), null)
        TextRequest(HttpVerb.POST, "/runEnvironment", listOf("envKey" to "env-1"), null)
        TextRequest(HttpVerb.POST, "/sendMessageToFeature", listOf("envKey" to "env-1", "featureName" to "TickByTick"), "pause")

        assertFailsWith<IllegalArgumentException> { TextRequest(HttpVerb.PUT, "/setModel", listOf("envKey" to "env-1"), null) }
        assertFailsWith<IllegalArgumentException> { TextRequest(HttpVerb.GET, "/health", emptyList(), "") }
    }

    @Test
    fun `closing the transports releases them once however often it is called`() {
        var releases = 0
        val transports = RuntimeTransports(
            http = { HttpOutcome.Failed(TransportFailure.Unreachable("unused")) },
            webSockets = { ConnectOutcome.Failed(TransportFailure.Unreachable("unused")) },
            release = { releases++ },
        )

        transports.close()
        transports.close()

        assertEquals(1, releases)
    }
}
