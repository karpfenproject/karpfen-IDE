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

import io.karpfen.ide.runtime.protocol.transport.LinkClosure
import io.karpfen.ide.runtime.protocol.transport.LinkFrame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText

/**
 * The pure mappings between Ktor's WebSocket values and the client's link values, kept apart from the live session so
 * they are tested on their own. Stateless.
 */
internal object WebSocketFrames {

    /** The link frame of [frame]: text and binary frames; null for the control frames, which Ktor answers itself. */
    fun linkFrameOf(frame: Frame): LinkFrame? =
        when (frame) {
            is Frame.Text -> LinkFrame.Text(frame.readText())
            is Frame.Binary -> LinkFrame.Binary(frame.data.size)
            else -> null
        }

    /** How the link closed, from the close [reason] Ktor reports (null without a close frame) and who closed it. */
    fun closureOf(reason: CloseReason?, byClient: Boolean): LinkClosure =
        LinkClosure(code = reason?.code?.toInt(), reason = reason?.message, byClient = byClient)

    /** The Ktor close reason of a close with [code] and [reason]. */
    fun closeReasonOf(code: Int, reason: String): CloseReason = CloseReason(code.toShort(), reason)
}
