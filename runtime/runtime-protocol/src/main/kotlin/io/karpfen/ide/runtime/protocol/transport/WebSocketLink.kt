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

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel

/** How an attempt to open a WebSocket to the runtime ended. */
public sealed interface ConnectOutcome {
    /**
     * The WebSocket is open; nothing was sent on it yet.
     *
     * @property link the open link.
     */
    public data class Connected(public val link: WebSocketLink) : ConnectOutcome

    /**
     * The WebSocket could not be opened.
     *
     * @property failure why.
     */
    public data class Failed(public val failure: TransportFailure) : ConnectOutcome
}

/**
 * The WebSocket seam of the client: opens one WebSocket to a URL such as `ws://127.0.0.1:8080/ws`. The Ktor adapter
 * implements it in production, the fake runtime in tests.
 *
 * Threading (MUST): may be called from any coroutine, concurrently; never blocks a thread.
 *
 * Contract (MUST): a failure to connect is a [ConnectOutcome.Failed], never an exception; a `CancellationException` of
 * the caller is rethrown. A connected link has sent nothing: the handshake is the caller's first [WebSocketLink.send].
 */
public fun interface WebSocketConnector {
    /** Opens a WebSocket to [url]. */
    public suspend fun connect(url: String): ConnectOutcome
}

/**
 * One open WebSocket to the runtime, as text frames.
 *
 * Threading (MUST): [incoming] has one collector; [send] and [close] may be called from any coroutine, and the caller
 * serializes its sends. Nothing blocks a thread.
 *
 * Contract (MUST):
 * - [incoming] delivers the frames the runtime sent, in order, and is closed when the link closes.
 * - [closure] completes exactly once, when the link closed for any reason, with the close code and reason of the close
 *   frame (both null without one) and whether the client closed it.
 * - [send] reports a failure as a [LinkSendResult.Failed], never as an exception, except a `CancellationException` of
 *   the caller.
 * - [close] sends a close frame with the given code and reason, if the link is still open, and returns once the close
 *   frame was handed over; closing a closed link does nothing.
 */
public interface WebSocketLink {
    /** The frames the runtime sends, in order; closed when the link closes. */
    public val incoming: ReceiveChannel<LinkFrame>

    /** How the link closed; completes once, when it does. */
    public val closure: Deferred<LinkClosure>

    /** Sends [text] as one text frame. */
    public suspend fun send(text: String): LinkSendResult

    /** Closes the link with [code] and [reason]. */
    public suspend fun close(code: Int, reason: String)
}

/** One frame the runtime sent on a link. */
public sealed interface LinkFrame {
    /**
     * A text frame.
     *
     * @property text its text.
     */
    public data class Text(public val text: String) : LinkFrame

    /**
     * A binary frame, which the runtime never sends; only its size is kept.
     *
     * @property size its size in bytes.
     */
    public data class Binary(public val size: Int) : LinkFrame
}

/**
 * How a link closed.
 *
 * @property code the code of the close frame, or null if the link closed without one (a broken connection).
 * @property reason the reason of the close frame, or null without one.
 * @property byClient true if the client closed the link, false if the runtime or the network did.
 */
public data class LinkClosure(public val code: Int?, public val reason: String?, public val byClient: Boolean)

/** How sending a frame ended. */
public sealed interface LinkSendResult {
    /** The frame was handed to the connection. */
    public data object Sent : LinkSendResult

    /**
     * The frame could not be sent.
     *
     * @property failure why.
     */
    public data class Failed(public val failure: TransportFailure) : LinkSendResult
}
