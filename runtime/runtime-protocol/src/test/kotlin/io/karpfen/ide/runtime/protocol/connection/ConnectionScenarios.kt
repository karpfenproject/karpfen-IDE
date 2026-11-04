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
import io.karpfen.ide.runtime.protocol.catalogue.HttpVerb
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.conformance.RuntimeUnderTest
import io.karpfen.ide.runtime.protocol.conformance.awaitValue
import io.karpfen.ide.runtime.protocol.conformance.eventually
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.Observatory
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.EventFrameEncoder
import io.karpfen.ide.runtime.protocol.message.EventPayload
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.message.WireValue
import io.karpfen.ide.runtime.protocol.transport.ConnectOutcome
import io.karpfen.ide.runtime.protocol.transport.HttpOutcome
import io.karpfen.ide.runtime.protocol.transport.LinkSendResult
import io.karpfen.ide.runtime.protocol.transport.TextRequest
import io.karpfen.ide.runtime.protocol.transport.ktor.RawWebSocketProbe
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers the client's connection against a real runtime, the only test of the Ktor WebSocket adapter with a live
 * socket: the silent handshake and the first stacks after the start; an injected event coming back as pushes; refused
 * handshakes (a used key ends the connection as rejected, a malformed handshake and a binary first frame close the
 * socket with their reasons); a link the client closes replaced on a fresh key with the same client id, receiving
 * pushes again after the subscription is confirmed; the runtime's close at a stop ending the connection as stopped; and
 * text with spaces and an en dash surviving the wire in snapshots, stacks, forced-state requests and pushed values.
 *
 * The scenarios run only against a real runtime ([LiveConnectionScenariosTest]); a half-open old link that heals
 * through a revision gap is a failure only a fake can produce and is an ordinary test against the fake runtime. Every
 * scenario stops the environments it deployed when it ends, also when it fails.
 */
abstract class ConnectionScenarios {

    /** The runtime the scenarios run against. */
    protected abstract val runtime: RuntimeUnderTest

    /** Where that runtime listens. */
    protected abstract val endpoint: RuntimeEndpoint

    @Test
    fun `the handshake is silent and the first states follow the start`() = scenario {
        val environment = deployLamp()
        val connection = connect(environment)
        assertEquals(ConnectionState.Open(1), connection.connection.state.value, "open without any acknowledgement")
        subscribe(environment, connection.connection.clientId)
        scope.awaitValue("the open event", connection.events) { it.isNotEmpty() }
        assertEquals(listOf<ConnectionEvent>(ConnectionEvent.Opened(1)), connection.events.value, "nothing arrives before the start")

        assertIs<CallOutcome.Done<Unit>>(environment.activate())
        assertIs<CallOutcome.Done<Unit>>(environment.start())

        connection.awaitStack("lamp", listOf("off"))
        connection.awaitStack("wallSwitch", listOf("down"))
        assertTrue(connection.events.value.none { it is ConnectionEvent.Violated }, "${connection.events.value}")
        assertIs<CallOutcome.Done<Unit>>(environment.stop())
    }

    @Test
    fun `an injected event comes back as pushes`() = scenario {
        val (environment, connection) = runningLamp()

        val sent = connection.connection.send(EventFrame("switch", "press", EventPayload.None))

        assertEquals(SendOutcome.Written(EventFrameEncoder.encode(EventFrame("switch", "press", EventPayload.None))), sent)
        scope.eventually("the switch's position up") {
            connection.values("wallSwitch").lastOrNull()?.takeIf { text(it, "position") == "up" }
        }
        connection.awaitStack("wallSwitch", listOf("up"))
        connection.awaitStack("lamp", listOf("on"))
        assertIs<CallOutcome.Done<Unit>>(environment.stop())
    }

    @Test
    fun `refused handshakes end the connection or close the socket with their reasons`() = scenario {
        val environment = deployLamp()
        val clientId = clientId()
        val key = assertIs<CallOutcome.Done<AccessKey>>(
            environment.issueAccessKey(clientId, Observatory.NoMachine),
        ).value
        val first = connect(environment, clientId) { CallOutcome.Done(key) }
        subscribe(environment, clientId)

        val second = connect(environment, clientId) { CallOutcome.Done(key) }
        val ended = scope.awaitValue("the end of the connection with a used key", second.connection.state) { it is ConnectionState.Ended }

        assertEquals(ConnectionEnd.HandshakeRejected(HandshakeRejection.InvalidCredentials), (ended as ConnectionState.Ended).end)
        assertEquals(ConnectionState.Open(1), first.connection.state.value)
        val malformed = assertIs<ConnectOutcome.Connected>(scope.transports.webSockets.connect(endpoint.webSocketUrl)).link
        assertEquals(LinkSendResult.Sent, malformed.send("a:b"))
        assertRuntimeClosed(
            WireNames.CLOSE_CANNOT_ACCEPT, WireNames.REASON_INVALID_AUTH_FORMAT,
            withTimeout(scope.patience.reaction) { malformed.closure.await() },
        )
        assertRuntimeClosed(
            WireNames.CLOSE_CANNOT_ACCEPT, WireNames.REASON_FIRST_MESSAGE_NOT_TEXT,
            RawWebSocketProbe.closureAfterBinaryFirstFrame(endpoint.webSocketUrl, scope.patience.reaction),
        )
        first.connection.disconnect()
    }

    @Test
    fun `a link the client closes is replaced on a fresh key with the same client id and receives pushes again`() = scenario {
        val (environment, connection) = runningLamp()
        val clientId = connection.connection.clientId
        val replaced = links.opened.single()

        replaced.close(WireNames.CLOSE_NORMAL, "Replaced by the scenario")
        scope.awaitValue("the second open", connection.connection.state) { it == ConnectionState.Open(2) }
        subscribe(environment, clientId)
        connection.connection.send(EventFrame("switch", "press", EventPayload.None))

        connection.awaitStack("wallSwitch", listOf("up"), epoch = 2)
        val handshakes = links.opened.map { it.sent.first() }
        assertEquals(2, handshakes.size)
        assertTrue(handshakes.all { it.startsWith("${clientId.value}:${environment.key.value}:") }, "$handshakes")
        assertTrue(handshakes[0] != handshakes[1], "a fresh key for the new link: $handshakes")
        assertTrue(connection.events.value.any { it is ConnectionEvent.Interrupted && it.closure.byClient }, "${connection.events.value}")
        assertIs<CallOutcome.Done<Unit>>(environment.stop())
    }

    @Test
    fun `the runtime's close at a stop ends the connection as stopped`() = scenario {
        val (environment, connection) = runningLamp()
        val link = links.opened.single()
        val stop = TextRequest(HttpVerb.POST, RuntimeRoute.STOP_ENVIRONMENT.path, listOf("envKey" to environment.key.value), null)

        assertEquals(200, assertIs<HttpOutcome.Answered>(scope.transports.http.exchange(stop)).status)

        val ended = scope.awaitValue("the end of the connection", connection.connection.state) { it is ConnectionState.Ended }
        assertEquals(ConnectionEnd.EnvironmentStopped, (ended as ConnectionState.Ended).end)
        assertRuntimeClosed(WireNames.CLOSE_NORMAL, WireNames.REASON_ENVIRONMENT_STOPPED, link.closure.await())
    }

    @Test
    fun `text with spaces and an en dash survives the wire`() = scenario {
        val (environment, connection) = runningLamp()

        val snapshot = assertIs<CallOutcome.Done<RuntimeModelSnapshot>>(environment.snapshot()).value
        assertEquals("living room", text(snapshot.roots.single(), WireNames.OBJECT_ID_MEMBER))
        connection.connection.send(EventFrame("switch", "press", EventPayload.None))
        connection.awaitStack("lamp", listOf("on"))
        connection.connection.send(EventFrame("switch", "dim", EventPayload.Json("""{"bright": true}""")))
        connection.awaitStack("lamp", listOf("on", "full power"))
        val log = scope.eventually("the full power entry in the lamp's log") {
            connection.values("lamp").lastOrNull()?.let { it["log"] as? WireValue.WireArray }?.takeIf { it.items.size == 3 }
        }

        assertEquals(
            listOf("off", "on", "100 W – full power"),
            log.items.map { (it as WireValue.WireString).value },
        )
        assertIs<CallOutcome.Done<Unit>>(environment.forceState("lamp", "full power"))
        assertTrue(connection.pushes.none { it is RuntimePush.Unrecognized })
        assertIs<CallOutcome.Done<Unit>>(environment.stop())
    }

    /** Runs [body] as a scenario against [runtime] with a real client over its transports, which stops what it deployed at the end. */
    private fun scenario(body: suspend ScenarioClient.() -> Unit) {
        runtime.runClient(endpoint, body)
    }

    /** The string member [name] of [value]. */
    private fun text(value: WireValue.WireObject, name: String): String? = (value[name] as? WireValue.WireString)?.value
}
