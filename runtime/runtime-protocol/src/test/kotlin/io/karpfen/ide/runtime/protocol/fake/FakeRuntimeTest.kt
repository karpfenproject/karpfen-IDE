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
package io.karpfen.ide.runtime.protocol.fake

import io.karpfen.ide.runtime.protocol.catalogue.HttpVerb
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.golden.RuntimeGoldens
import io.karpfen.ide.runtime.protocol.transport.ConnectOutcome
import io.karpfen.ide.runtime.protocol.transport.HttpOutcome
import io.karpfen.ide.runtime.protocol.transport.LinkClosure
import io.karpfen.ide.runtime.protocol.transport.LinkFrame
import io.karpfen.ide.runtime.protocol.transport.LinkSendResult
import io.karpfen.ide.runtime.protocol.transport.TextRequest
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import io.karpfen.ide.runtime.protocol.transport.WebSocketLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the fake runtime. Programmed answers, failures, delays and silences come back as programmed. They are queued
 * per route in programming order and matched on parameters. Routes without a program have fixed defaults. Follow-ups
 * attached to a request run after its answer. Links record the handshake and the client's frames. They accept the
 * handshake silently or close it as programmed, deliver pushed frames, and close or drop as the test says. The tests
 * also cover refused and hanging connects, and the recording of requests, handshakes and frames. The fake decides
 * nothing, so nothing else is tested.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeRuntimeTest {

    @Test
    fun `programmed answers come back in programming order, matched on parameters, and then the default`() = runTest {
        val fake = FakeRuntime(backgroundScope)
            .respond(
                RuntimeRoute.SET_MODEL,
                FakeResponse.rejected(409, RuntimeText.METAMODEL_REQUIRED, mapOf("env" to "env-2")),
                where = mapOf("envKey" to "env-2"),
            )
            .respond(RuntimeRoute.SET_MODEL, FakeResponse(400, "first"))
            .respond(RuntimeRoute.SET_MODEL, FakeResponse(500, "second", "text/plain"))

        val answers = listOf("env-1", "env-2", "env-1", "env-1").map { key -> fake.exchange(setModel(key)) }

        assertEquals(
            listOf(
                HttpOutcome.Answered(400, "first", null),
                HttpOutcome.Answered(409, "Metamodel must be set before receiving a model for environment env-2", FakeResponse.TEXT),
                HttpOutcome.Answered(500, "second", "text/plain"),
                HttpOutcome.Answered(200, "", FakeResponse.TEXT),
            ),
            answers,
        )
    }

    @Test
    fun `programmed failures, delays and silences come back as programmed on the caller's time`() = runTest {
        val fake = FakeRuntime(backgroundScope)
            .fail(RuntimeRoute.HEALTH, TransportFailure.Unreachable("Connection refused"))
            .respond(RuntimeRoute.HEALTH, FakeResponse.ok("late"), delay = 5.seconds)
            .fail(RuntimeRoute.HEALTH, TransportFailure.Interrupted("reset"), delay = 2.seconds)
            .hang(RuntimeRoute.HEALTH)

        assertEquals(HttpOutcome.Failed(TransportFailure.Unreachable("Connection refused")), fake.exchange(health()))
        assertEquals(HttpOutcome.Answered(200, "late", FakeResponse.TEXT), fake.exchange(health()))
        assertEquals(5.seconds.inWholeMilliseconds, testScheduler.currentTime)
        assertEquals(HttpOutcome.Failed(TransportFailure.Interrupted("reset")), fake.exchange(health()))
        val silent = async { fake.exchange(health()) }
        advanceTimeBy(1_000.seconds)
        assertFalse(silent.isCompleted, "a silenced request is never answered")
        silent.cancel()
    }

    @Test
    fun `routes without a program answer the fixed defaults`() = runTest {
        val fake = FakeRuntime(backgroundScope)

        val bodies = listOf(
            fake.exchange(post(RuntimeRoute.CREATE_ENVIRONMENT)),
            fake.exchange(post(RuntimeRoute.CREATE_ENVIRONMENT)),
            fake.exchange(post(RuntimeRoute.REGISTER_CLIENT_FOR_WEB_SOCKET, "clientId" to "ide-a", "envKey" to "env-1")),
            fake.exchange(
                post(RuntimeRoute.REGISTER_OBSERVATORY_CLIENT, "clientId" to "ide-b", "envKey" to "env-1", "modelElement" to "*"),
            ),
            fake.exchange(health()),
            fake.exchange(get(RuntimeRoute.MODEL_SNAPSHOT, "envKey" to "env-1")),
            fake.exchange(post(RuntimeRoute.RUN_ENVIRONMENT, "envKey" to "env-1")),
            fake.exchange(TextRequest(HttpVerb.GET, "/version", emptyList(), null)),
        )

        assertEquals(
            listOf(
                HttpOutcome.Answered(200, "env-1", FakeResponse.TEXT),
                HttpOutcome.Answered(200, "env-2", FakeResponse.TEXT),
                HttpOutcome.Answered(200, "ak-ide-a-1761555601234-1", FakeResponse.TEXT),
                HttpOutcome.Answered(200, "ak-ide-b-1761555601234-2", FakeResponse.TEXT),
                HttpOutcome.Answered(200, """{"status":"ok"}""", "application/json"),
                HttpOutcome.Answered(404, "", null),
                HttpOutcome.Answered(200, "", FakeResponse.TEXT),
                HttpOutcome.Answered(404, "", null),
            ),
            bodies,
        )
        assertEquals("env-3", FakeRuntime.environmentKey(3))
        assertEquals("ak-ide-a-1761555601234-3", FakeRuntime.accessKey("ide-a", 3))
    }

    @Test
    fun `every request is recorded with its route, parameters and body`() = runTest {
        val fake = FakeRuntime(backgroundScope)

        fake.exchange(setModel("env-1"))
        fake.exchange(TextRequest(HttpVerb.POST, "/unknown", listOf("a" to "b"), null))

        assertEquals(
            listOf(
                FakeRequest(RuntimeRoute.SET_MODEL, HttpVerb.PUT, "/setModel", listOf("envKey" to "env-1"), MODEL),
                FakeRequest(null, HttpVerb.POST, "/unknown", listOf("a" to "b"), null),
            ),
            fake.requests,
        )
        assertEquals(listOf("env-1"), fake.requests(RuntimeRoute.SET_MODEL).map { it.parameter("envKey") })
        assertNull(fake.requests.first().parameter("clientId"))
    }

    @Test
    fun `a follow-up runs once after the answer to the matching request`() = runTest {
        val fake = FakeRuntime(backgroundScope)
        val link = connect(fake)
        link.send("ide-a:env-1:ak-ide-a-1-1")
        val followed = ArrayList<String>()
        fake.after(RuntimeRoute.SUBSCRIBE_MODEL, where = mapOf("clientId" to "ide-a")) { request ->
            followed += request.parameter("envKey").orEmpty()
            pushToClient("ide-a", RuntimeGoldens.STATE_CHANGE)
        }

        fake.exchange(post(RuntimeRoute.SUBSCRIBE_MODEL, "envKey" to "env-1", "clientId" to "ide-b"))
        runCurrent()
        assertEquals(emptyList(), followed, "the follow-up waits for its matching request")
        fake.exchange(post(RuntimeRoute.SUBSCRIBE_MODEL, "envKey" to "env-1", "clientId" to "ide-a"))
        assertEquals(emptyList(), followed, "the follow-up runs after the answer")
        runCurrent()
        fake.exchange(post(RuntimeRoute.SUBSCRIBE_MODEL, "envKey" to "env-1", "clientId" to "ide-a"))
        runCurrent()

        assertEquals(listOf("env-1"), followed)
        assertEquals(LinkFrame.Text(RuntimeGoldens.STATE_CHANGE), link.incoming.receive())
    }

    @Test
    fun `a link records the handshake and the client's frames, and accepts the handshake silently`() = runTest {
        val fake = FakeRuntime(backgroundScope)
        val link = connect(fake)

        assertEquals(LinkSendResult.Sent, link.send(RuntimeGoldens.HANDSHAKE))
        assertEquals(LinkSendResult.Sent, link.send(RuntimeGoldens.START_EVENT_FRAME))
        link.send(RuntimeGoldens.ASSIGN_DELIVERY_EVENT_FRAME)

        val fakeLink = fake.links.single()
        assertTrue(fakeLink.isOpen)
        assertEquals(RuntimeGoldens.HANDSHAKE, fakeLink.handshake)
        assertEquals(listOf(RuntimeGoldens.START_EVENT_FRAME, RuntimeGoldens.ASSIGN_DELIVERY_EVENT_FRAME), fakeLink.clientFrames)
        assertEquals(listOf(RuntimeGoldens.HANDSHAKE), fake.handshakes)
        assertEquals(fakeLink.clientFrames, fake.clientFrames)
        assertEquals(listOf("ws://127.0.0.1:8080/ws"), fake.connectedUrls)
        assertEquals(listOf(fakeLink), fake.linksOf("ide-4f2c"))
        assertTrue(fakeLink.drained)
    }

    @Test
    fun `a programmed handshake close closes the next link as soon as its handshake arrived`() = runTest {
        val fake = FakeRuntime(backgroundScope).closeHandshake(1003, "Invalid credentials")
        val refused = connect(fake)
        val accepted = connect(fake)

        refused.send("ide-a:env-1:ak-used")
        accepted.send("ide-a:env-1:ak-fresh")

        assertEquals(LinkClosure(1003, "Invalid credentials", byClient = false), refused.closure.await())
        assertTrue(refused.incoming.receiveCatching().isClosed)
        assertFalse(accepted.closure.isCompleted)
        assertIs<LinkSendResult.Failed>(refused.send("late"))
    }

    @Test
    fun `pushed frames arrive in order, and the runtime's closes and drops end the link`() = runTest {
        val fake = FakeRuntime(backgroundScope)
        val first = connect(fake)
        val second = connect(fake)
        first.send("ide-a:env-1:ak-1")
        second.send("ide-b:env-1:ak-2")

        fake.pushToClient("ide-a", RuntimeGoldens.OBJECT_CHANGED)
        assertTrue(fake.links[0].push(RuntimeGoldens.OBJECT_DELETED))
        assertTrue(fake.links[0].pushBinary(3))
        fake.links[0].closeByRuntime(1000, "Environment stopped")
        fake.links[1].drop()

        assertEquals(LinkFrame.Text(RuntimeGoldens.OBJECT_CHANGED), first.incoming.receive())
        assertEquals(LinkFrame.Text(RuntimeGoldens.OBJECT_DELETED), first.incoming.receive())
        assertEquals(LinkFrame.Binary(3), first.incoming.receive())
        assertTrue(first.incoming.receiveCatching().isClosed)
        assertEquals(LinkClosure(1000, "Environment stopped", byClient = false), first.closure.await())
        assertEquals(LinkClosure(null, null, byClient = false), second.closure.await())
        assertFalse(fake.links[0].push(RuntimeGoldens.STATE_CHANGE), "a closed link takes no frames")
    }

    @Test
    fun `the client's close is recorded and pushing to a client needs exactly one open link`() = runTest {
        val fake = FakeRuntime(backgroundScope)
        val first = connect(fake)
        first.send("ide-a:env-1:ak-1")
        val second = connect(fake)
        second.send("ide-a:env-1:ak-2")

        assertFailsWith<IllegalStateException> { fake.pushToClient("ide-a", RuntimeGoldens.STATE_CHANGE) }
        assertFailsWith<IllegalStateException> { fake.pushToClient("ide-c", RuntimeGoldens.STATE_CHANGE) }
        first.close(1000, "Client closed the connection")
        fake.pushToClient("ide-a", RuntimeGoldens.STATE_CHANGE)

        assertEquals(listOf(FakeClose(1000, "Client closed the connection")), fake.links[0].clientCloses)
        assertEquals(LinkClosure(1000, "Client closed the connection", byClient = true), first.closure.await())
        assertEquals(LinkFrame.Text(RuntimeGoldens.STATE_CHANGE), second.incoming.receive())
    }

    @Test
    fun `connects fail or hang as programmed and open links again afterwards`() = runTest {
        val fake = FakeRuntime(backgroundScope).refuseConnect(TransportFailure.Unreachable("Connection refused")).hangConnect()

        assertEquals(ConnectOutcome.Failed(TransportFailure.Unreachable("Connection refused")), fake.connect(URL))
        val hanging = async { fake.connect(URL) }
        advanceTimeBy(1_000.seconds)
        assertFalse(hanging.isCompleted)
        hanging.cancel()
        assertIs<ConnectOutcome.Connected>(fake.connect(URL))
        assertEquals(listOf(URL, URL, URL), fake.connectedUrls)
        assertEquals(1, fake.links.size)
    }

    @Test
    fun `the fake's transports are its two seams`() = runTest {
        val fake = FakeRuntime(backgroundScope)

        fake.transports().use { transports ->
            assertTrue(transports.http === fake)
            assertTrue(transports.webSockets === fake)
        }
    }

    /** Opens a link to the fake. */
    private suspend fun connect(fake: FakeRuntime): WebSocketLink =
        assertIs<ConnectOutcome.Connected>(fake.connect(URL)).link

    /** A `PUT /setModel` of [environment] with the lamp's model text. */
    private fun setModel(environment: String): TextRequest =
        TextRequest(HttpVerb.PUT, RuntimeRoute.SET_MODEL.path, listOf("envKey" to environment), MODEL)

    /** A `GET /health`. */
    private fun health(): TextRequest = get(RuntimeRoute.HEALTH)

    /** A GET of [route] with [parameters]. */
    private fun get(route: RuntimeRoute, vararg parameters: Pair<String, String>): TextRequest =
        TextRequest(HttpVerb.GET, route.path, parameters.toList(), null)

    /** A POST of [route] with [parameters]. */
    private fun post(route: RuntimeRoute, vararg parameters: Pair<String, String>): TextRequest =
        TextRequest(HttpVerb.POST, route.path, parameters.toList(), null)

    private companion object {
        /** The URL the tests connect to. */
        const val URL = "ws://127.0.0.1:8080/ws"

        /** A model text. */
        const val MODEL = "make object \"living room\":\"Room\" { }"
    }
}
