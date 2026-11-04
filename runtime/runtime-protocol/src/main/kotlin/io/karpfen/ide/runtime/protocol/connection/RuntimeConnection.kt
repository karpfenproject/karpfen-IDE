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
package io.karpfen.ide.runtime.protocol.connection

import io.karpfen.ide.runtime.protocol.catalogue.AccessKey
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.message.EventFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * Issues the single-use access key for one connection attempt. By default the connection asks the environment handle
 * for a key without an observatory registration (`/registerClientForWebSocket`).
 *
 * Threading (MUST): called from the connection's coroutine; may suspend.
 *
 * Contract (MUST): every call issues a fresh key; a refusal or failure is an outcome, never an exception, except a
 * `CancellationException` of the caller.
 */
public fun interface AccessKeyIssuer {
    /** Issues a fresh access key. */
    public suspend fun issue(): CallOutcome<AccessKey>
}

/**
 * The authenticated push channel of one client to one environment: a WebSocket that is opened with a fresh access key,
 * authenticated with the handshake `<clientId>:<envKey>:<accessKey>`, read without ever stalling the runtime, and
 * reopened with the [ReconnectPolicy] when it drops. Subscriptions belong to the client id on the runtime, so they
 * survive reconnects.
 *
 * The runtime acknowledges no handshake: [ConnectionState.Open] means the handshake was sent and not rejected. A close
 * with 1003 ends the connection with [ConnectionEnd.HandshakeRejected], a close with `Environment stopped` or a stopped
 * environment with [ConnectionEnd.EnvironmentStopped], a lost environment with [ConnectionEnd.EnvironmentLost]; any other
 * close the client did not ask for emits [ConnectionEvent.Interrupted] and reconnects.
 *
 * Threading (MUST): every function may be called from any coroutine. The connection runs in a supervisor child of the
 * scope it was created in; [state] has the connection as its one writer; the reader of a link only decodes frames and
 * hands the events on without suspending, so a slow consumer never stalls the runtime's one broadcaster.
 *
 * Contract (MUST):
 * - [events] has exactly one collector and buffers without bound until it collects; pushes, violations and the
 *   boundaries of epochs travel in it in arrival order.
 * - A protocol violation never closes the link.
 * - Sends are serialized; [send] checks the environment's phase and the connection's state before it writes.
 * - [disconnect] and [close] end the connection with [ConnectionEnd.Disconnected]; [close] is idempotent and does not
 *   suspend. A cancellation of the scope the connection was created in ends it the same way, also before [open], so
 *   [open] never waits for a connection whose scope is gone.
 * - A `CancellationException` of the caller is always rethrown.
 */
public interface RuntimeConnection : AutoCloseable {
    /** The environment the connection belongs to. */
    public val environment: EnvironmentKey

    /** The client id the connection authenticates as, for its whole life. */
    public val clientId: ClientId

    /** The connection's state. */
    public val state: StateFlow<ConnectionState>

    /** What the connection reports, in order: openings, pushes, violations, interruptions and the end. */
    public val events: Flow<ConnectionEvent>

    /**
     * Connects, with the policy's backoff between failed attempts, until the connection is open or ended, and answers
     * that state. Every attempt takes a fresh access key and closes a link that is still open first, since the runtime
     * would keep pushing to the first session of the client id.
     */
    public suspend fun open(): ConnectionState

    /** Suspends until the connection is open or ended, or [within] passed, and answers the state then. */
    public suspend fun awaitOpen(within: Duration): ConnectionState

    /**
     * Sends [frame] as one text frame: refused when the environment's phase does not allow injecting events, not sent
     * unless the connection is open.
     */
    public suspend fun send(frame: EventFrame): SendOutcome

    /** Closes the link with a normal close and ends the connection; completes even when the caller is cancelled. */
    public suspend fun disconnect()

    /** Ends the connection without suspending: cancels its work and closes the link as far as possible; idempotent. */
    override fun close()
}
