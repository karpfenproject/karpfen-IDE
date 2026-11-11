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

import io.karpfen.ide.runtime.protocol.transport.LinkClosure

/** Why the runtime refused a WebSocket handshake. The runtime refuses by closing the link with 1003 and a reason. */
public sealed interface HandshakeRejection {
    /** The first frame was not text (`First message must be text`). */
    public data object FirstMessageNotText : HandshakeRejection

    /** The handshake was not three parts separated by `:` (`Invalid auth format`). */
    public data object InvalidAuthFormat : HandshakeRejection

    /** The access key was unknown, used or issued to another client or environment (`Invalid credentials`). */
    public data object InvalidCredentials : HandshakeRejection

    /**
     * A close with 1003 and another reason.
     *
     * @property code the close code.
     * @property reason the close reason, or null without one.
     */
    public data class Other(public val code: Int, public val reason: String?) : HandshakeRejection
}

/** Why a connection ended for good. An ended connection never reconnects. */
public sealed interface ConnectionEnd {
    /**
     * The runtime refused the handshake.
     *
     * @property rejection how.
     */
    public data class HandshakeRejected(public val rejection: HandshakeRejection) : ConnectionEnd

    /**
     * The environment was stopped. Either the runtime closed the link with `Environment stopped`, or the handle saw the
     * stop.
     */
    public data object EnvironmentStopped : ConnectionEnd

    /**
     * The runtime no longer knows the environment.
     *
     * @property reason why the client concluded so, in words.
     */
    public data class EnvironmentLost(public val reason: String) : ConnectionEnd

    /** The client disconnected. */
    public data object Disconnected : ConnectionEnd

    /**
     * Every attempt to connect failed, up to the reconnect policy's limit.
     *
     * @property attempts the number of failed attempts.
     * @property lastFailure why the last one failed, in words.
     */
    public data class GaveUp(public val attempts: Int, public val lastFailure: String) : ConnectionEnd
}

/** The state of a connection. */
public sealed interface ConnectionState {
    /** Not opened yet. */
    public data object Idle : ConnectionState

    /**
     * Connecting and authenticating.
     *
     * @property attempt the number of the attempt, counting from 1.
     */
    public data class Connecting(public val attempt: Int) : ConnectionState

    /**
     * The handshake was sent and not rejected. The runtime acknowledges nothing, so this is all a client can know.
     *
     * @property epoch the number of the successful open, counting from 1. Every reconnect increases it.
     */
    public data class Open(public val epoch: Int) : ConnectionState

    /**
     * The link closed without the client asking. The connection reconnects after the policy's delay.
     *
     * @property attempt the number of the next attempt, counting from 1.
     * @property after how the link closed, or null if the last attempt never opened one.
     */
    public data class Reconnecting(public val attempt: Int, public val after: LinkClosure?) : ConnectionState

    /**
     * The connection ended for good.
     *
     * @property end why.
     */
    public data class Ended(public val end: ConnectionEnd) : ConnectionState
}
