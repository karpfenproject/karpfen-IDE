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
package io.karpfen.ide.runtime.protocol.transport.ktor

import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import io.karpfen.ide.runtime.protocol.transport.TransportPhase
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [TransportFailures]. Failures to connect are unreachable or a timeout while connecting. Timeouts after the
 * request was handed over are timeouts while awaiting the answer. Everything else is an interruption. Causes are
 * followed, also through a cycle, and a failure without a message is described by its class.
 */
class TransportFailuresTest {

    @Test
    fun `a refused connection, an unknown or unresolved host and no route are unreachable`() {
        assertEquals(TransportFailure.Unreachable("Connection refused"), TransportFailures.classify(ConnectException("Connection refused")))
        assertEquals(TransportFailure.Unreachable("runtime.invalid"), TransportFailures.classify(UnknownHostException("runtime.invalid")))
        assertEquals(TransportFailure.Unreachable("No route to host"), TransportFailures.classify(NoRouteToHostException("No route to host")))
        assertEquals(TransportFailure.Unreachable("UnresolvedAddressException"), TransportFailures.classify(UnresolvedAddressException()))
    }

    @Test
    fun `a connect timeout is a timeout while connecting, although it is a connect exception`() {
        assertEquals(
            TransportFailure.TimedOut(TransportPhase.CONNECTING, "Connect timeout has expired"),
            TransportFailures.classify(ConnectTimeoutException("Connect timeout has expired", null)),
        )
    }

    @Test
    fun `request and socket timeouts are timeouts while awaiting the answer`() {
        val request = HttpRequestTimeoutException("http://127.0.0.1:8080/stopEnvironment", 120_000L, null)

        assertEquals(TransportPhase.AWAITING_RESPONSE, (TransportFailures.classify(request) as TransportFailure.TimedOut).phase)
        assertEquals(
            TransportFailure.TimedOut(TransportPhase.AWAITING_RESPONSE, "Read timed out"),
            TransportFailures.classify(SocketTimeoutException("Read timed out")),
        )
    }

    @Test
    fun `anything else after the request was handed over is an interruption`() {
        assertEquals(TransportFailure.Interrupted("Connection reset by peer"), TransportFailures.classify(IOException("Connection reset by peer")))
        assertEquals(TransportFailure.Interrupted("IllegalStateException"), TransportFailures.classify(IllegalStateException()))
        assertEquals(TransportFailure.Interrupted("IOException"), TransportFailures.classify(IOException(" ")))
    }

    @Test
    fun `the chain of causes decides, described by the outermost failure`() {
        val wrapped = IOException("Failed to connect to /127.0.0.1:8080", ConnectException("Connection refused"))

        assertEquals(TransportFailure.Unreachable("Failed to connect to /127.0.0.1:8080"), TransportFailures.classify(wrapped))
    }

    @Test
    fun `a cyclic chain of causes ends as an interruption`() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)

        assertEquals(TransportFailure.Interrupted("first"), TransportFailures.classify(first))
    }
}
