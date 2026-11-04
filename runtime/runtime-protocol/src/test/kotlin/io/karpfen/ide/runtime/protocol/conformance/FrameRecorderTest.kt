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
package io.karpfen.ide.runtime.protocol.conformance

import io.karpfen.ide.runtime.protocol.fake.FakeRuntime
import io.karpfen.ide.runtime.protocol.golden.RuntimeGoldens
import io.karpfen.ide.runtime.protocol.transport.ConnectOutcome
import io.karpfen.ide.runtime.protocol.transport.LinkClosure
import io.karpfen.ide.runtime.protocol.transport.LinkFrame
import io.karpfen.ide.runtime.protocol.transport.WebSocketLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers the frame recorder of the live runtime under test, over the fake runtime's seams: the frames a link receives
 * reach the client unchanged and in order, together with the closure; after the scenario every recorded frame is decoded
 * for the environment and client of its link's handshake, so frames the decoder accepts give no violation, while a
 * frame it rejects, a binary frame and frames on a link without a valid handshake each give one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrameRecorderTest {

    @Test
    fun `recorded links forward every frame and the closure unchanged`() = runTest {
        val fake = FakeRuntime(backgroundScope)
        val recorder = FrameRecorder()
        val link = connect(recorder, fake, backgroundScope)

        link.send("${RuntimeGoldens.CLIENT}:${RuntimeGoldens.ENVIRONMENT}:ak-1")
        RuntimeGoldens.PUSHES.forEach { fake.links.single().push(it) }
        fake.links.single().closeByRuntime(1000, "Environment stopped")
        runCurrent()

        val forwarded = buildList { for (frame in link.incoming) add((frame as LinkFrame.Text).text) }
        assertEquals(RuntimeGoldens.PUSHES, forwarded)
        assertEquals(LinkClosure(1000, "Environment stopped", byClient = false), link.closure.await())
        assertEquals(emptyList(), recorder.violations())
    }

    @Test
    fun `frames the decoder rejects, binary frames and frames without a valid handshake are violations`() = runTest {
        val fake = FakeRuntime(backgroundScope)
        val recorder = FrameRecorder()
        val decoded = connect(recorder, fake, backgroundScope)
        val unnamed = connect(recorder, fake, backgroundScope)

        decoded.send("${RuntimeGoldens.CLIENT}:${RuntimeGoldens.ENVIRONMENT}:ak-1")
        unnamed.send("a:b")
        fake.links[0].push(RuntimeGoldens.STATE_CHANGE)
        fake.links[0].push("{\"environmentKey\":\"env-2\"")
        fake.links[0].push(RuntimeGoldens.OBJECT_CHANGED.replace(RuntimeGoldens.CLIENT, "someone-else"))
        fake.links[0].pushBinary(4)
        fake.links[1].push(RuntimeGoldens.STATE_CHANGE)
        runCurrent()

        val violations = recorder.violations()
        assertEquals(4, violations.size, "$violations")
        assertTrue("UNDECODABLE_FRAME" in violations[0], violations[0])
        assertTrue("FOREIGN_ENVELOPE" in violations[1], violations[1])
        assertTrue("binary frame of 4 bytes" in violations[2], violations[2])
        assertTrue("names no client" in violations[3], violations[3])
    }

    /** A link of the fake opened through the recorder, forwarding in [scope]. */
    private suspend fun connect(recorder: FrameRecorder, fake: FakeRuntime, scope: CoroutineScope): WebSocketLink =
        assertIs<ConnectOutcome.Connected>(recorder.recording(fake.transports(), scope).webSockets.connect(URL)).link

    private companion object {
        /** The URL the tests connect to. */
        const val URL = "ws://127.0.0.1:8080/ws"
    }
}
