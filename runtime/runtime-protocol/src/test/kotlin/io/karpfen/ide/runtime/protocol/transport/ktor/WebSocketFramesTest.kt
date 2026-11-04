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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [WebSocketFrames], the pure part of the WebSocket adapter: text frames keep their UTF-8 text, binary frames
 * their size, control frames are left to Ktor; a close reason becomes the link's closure with its code and message, no
 * close frame a closure without code; the client's close becomes Ktor's close reason.
 */
class WebSocketFramesTest {

    @Test
    fun `text frames keep their text and binary frames their size`() {
        val push = """{"environmentKey":"env-1","clientId":"ide-4f2c","messageType":"objectDeleted","payload":{"objectId":"on – off","revision":1}}"""

        assertEquals(LinkFrame.Text(push), WebSocketFrames.linkFrameOf(Frame.Text(push)))
        assertEquals(LinkFrame.Binary(3), WebSocketFrames.linkFrameOf(Frame.Binary(true, byteArrayOf(1, 2, 3))))
    }

    @Test
    fun `control frames are not handed to the link`() {
        assertNull(WebSocketFrames.linkFrameOf(Frame.Ping(byteArrayOf())))
        assertNull(WebSocketFrames.linkFrameOf(Frame.Pong(byteArrayOf())))
        assertNull(WebSocketFrames.linkFrameOf(Frame.Close(CloseReason(1000, "Environment stopped"))))
    }

    @Test
    fun `a close reason becomes the closure with its code and message, and none a closure without code`() {
        assertEquals(
            LinkClosure(1000, "Environment stopped", byClient = false),
            WebSocketFrames.closureOf(CloseReason(1000, "Environment stopped"), byClient = false),
        )
        assertEquals(
            LinkClosure(1003, "Invalid credentials", byClient = false),
            WebSocketFrames.closureOf(CloseReason(1003, "Invalid credentials"), byClient = false),
        )
        assertEquals(LinkClosure(null, null, byClient = false), WebSocketFrames.closureOf(null, byClient = false))
        assertEquals(LinkClosure(1000, "", byClient = true), WebSocketFrames.closureOf(CloseReason(1000, ""), byClient = true))
    }

    @Test
    fun `the client's close becomes Ktor's close reason`() {
        assertEquals(CloseReason(1000, "Client closed the connection"), WebSocketFrames.closeReasonOf(1000, "Client closed the connection"))
        assertEquals(CloseReason(CloseReason.Codes.NORMAL, "bye"), WebSocketFrames.closeReasonOf(1000, "bye"))
    }
}
