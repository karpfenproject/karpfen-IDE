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
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

/**
 * Classifies what Ktor throws by how far the exchange got, looking through the chain of causes: a failure to connect
 * (a refused connection, an unresolved host, no route to the host) is [TransportFailure.Unreachable], a connect timeout
 * [TransportFailure.TimedOut] while [TransportPhase.CONNECTING]; a request or socket timeout is
 * [TransportFailure.TimedOut] while [TransportPhase.AWAITING_RESPONSE]; anything else happened after the request was
 * handed to the connection and is [TransportFailure.Interrupted]. Stateless.
 */
internal object TransportFailures {

    /** The failure that [thrown] stands for. */
    fun classify(thrown: Throwable): TransportFailure {
        val cause = describe(thrown)
        for (link in generateSequence(thrown) { it.cause }.take(MAX_CAUSES)) {
            when (link) {
                is ConnectTimeoutException -> return TransportFailure.TimedOut(TransportPhase.CONNECTING, cause)
                is HttpRequestTimeoutException, is SocketTimeoutException ->
                    return TransportFailure.TimedOut(TransportPhase.AWAITING_RESPONSE, cause)
                is ConnectException, is UnresolvedAddressException, is UnknownHostException, is NoRouteToHostException ->
                    return TransportFailure.Unreachable(cause)
            }
        }
        return TransportFailure.Interrupted(cause)
    }

    /** [thrown] in words: its message, or its class name without one. */
    fun describe(thrown: Throwable): String = thrown.message?.takeIf { it.isNotBlank() } ?: thrown::class.java.simpleName

    /** The most causes followed, so that a cyclic chain of causes ends. */
    private const val MAX_CAUSES = 16
}
