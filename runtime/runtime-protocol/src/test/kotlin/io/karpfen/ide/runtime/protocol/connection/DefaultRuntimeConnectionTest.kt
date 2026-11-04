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

import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.AccessKey
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.doubles.Journal
import io.karpfen.ide.runtime.protocol.doubles.PhaseOnlyEnvironment
import io.karpfen.ide.runtime.protocol.doubles.ScriptedConnector
import io.karpfen.ide.runtime.protocol.golden.RuntimeWireWriter
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleRefusal
import io.karpfen.ide.runtime.protocol.lifecycle.PhaseKind
import io.karpfen.ide.runtime.protocol.lifecycle.Uploads
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.EventPayload
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.message.ViolationKind
import io.karpfen.ide.runtime.protocol.transport.LinkClosure
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Covers [DefaultRuntimeConnection] over scripted links on virtual time: the exact handshake and an open state without
 * any acknowledgement; pushes, violations and binary frames in order without closing the link; a reader that drains the
 * link while the collector is suspended; the ends by a close 1003 (also late), by the runtime's stop, by the handle's
 * phase, by a key the runtime refuses for an unknown environment, by the client, by a scope cancelled before the open
 * and by the attempt limit; reconnects after the policy's delay with a fresh key, the same client id, the old link
 * closed first and the next epoch; failed attempts with their backoff; [RuntimeConnection.awaitOpen]; and the checks of
 * [RuntimeConnection.send].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRuntimeConnectionTest {

    /** The environment of the connections. */
    private val environment = EnvironmentKey("env-1761555600000")

    /** The client id of the connections. */
    private val client = ClientId("ide-4f2c")

    /** Where the connections connect. */
    private val url = "ws://127.0.0.1:8080/ws"

    /** The record of the steps of the issuer, the connector and the links. */
    private val journal = Journal()

    /** The runtime's side of the WebSocket. */
    private val connector = ScriptedConnector(journal)

    /** The environment's handle, whose phase the tests set. */
    private val handle = PhaseOnlyEnvironment(environment, EnvironmentPhase.Running)

    /** The outcomes the issuer answers first, in order; once they are used up it issues fresh keys. */
    private val issuances = ArrayDeque<CallOutcome<AccessKey>>()

    /** The number of keys asked for so far. */
    private var issued = 0

    /** Issues the scripted outcomes and then the fresh key [key] of each call, recording every call. */
    private val issuer = AccessKeyIssuer {
        issued++
        journal.add("issue $issued")
        issuances.removeFirstOrNull() ?: CallOutcome.Done(AccessKey(key(issued)))
    }

    /**
     * A connection of the client to the environment in [parent], by default the background of the test, running on its
     * virtual time.
     */
    private fun TestScope.connection(
        policy: ReconnectPolicy = ReconnectPolicy(),
        parent: CoroutineScope = backgroundScope,
    ): DefaultRuntimeConnection =
        DefaultRuntimeConnection(handle, client, url, connector, issuer, policy, parent, StandardTestDispatcher(testScheduler))

    /** The events of [connection], collected in the background as they come, and the collecting job. */
    private fun TestScope.collect(connection: RuntimeConnection): Pair<List<ConnectionEvent>, Job> {
        val events = mutableListOf<ConnectionEvent>()
        val job = backgroundScope.launch { connection.events.collect { events += it } }
        return events to job
    }

    /** The fresh key of the [number]th issuance. */
    private fun key(number: Int): String = "ak-ide-4f2c-1761555601234-482$number"

    /** The handshake with the key of the [number]th issuance. */
    private fun handshake(number: Int): String = "ide-4f2c:env-1761555600000:${key(number)}"

    /** A push of [messageType] with [payload] to this client of this environment. */
    private fun frame(messageType: String, payload: String): String =
        RuntimeWireWriter.envelope(environment.value, client.value, messageType, payload)

    /** The deletion push of [objectId] at [revision]. */
    private fun deletion(objectId: String, revision: Long): String =
        frame("objectDeleted", RuntimeWireWriter.objectDeletedPayload(objectId, revision))

    @Test
    fun `opening sends exactly the handshake and is open without waiting for an acknowledgement`() = runTest {
        val connection = connection()
        val (events, _) = collect(connection)

        val state = connection.open()
        runCurrent()

        assertEquals(ConnectionState.Open(1), state)
        assertEquals(listOf(url), connector.connectedUrls)
        assertEquals(listOf("ide-4f2c:env-1761555600000:ak-ide-4f2c-1761555601234-4821"), connector.links.single().sent)
        assertEquals(listOf<ConnectionEvent>(ConnectionEvent.Opened(1)), events)
        assertEquals(listOf("issue 1", "connect", "link 1 sent ${handshake(1)}"), journal.steps)
        assertEquals(environment, connection.environment)
        assertEquals(client, connection.clientId)
    }

    @Test
    fun `pushes, violations and binary frames arrive in order, and no violation closes the link`() = runTest {
        val connection = connection()
        val (events, _) = collect(connection)
        connection.open()
        val link = connector.links.single()

        link.push(deletion("lamp", 1))
        link.push("not the runtime's JSON")
        link.pushBinary(4)
        link.push(frame("objectChanged", """{"objectId":"lamp","revision":2,"value":{"__id__": "switch", "__type__": "Switch"}}"""))
        link.push(frame("stateChange", RuntimeWireWriter.stateChangePayload("lamp", listOf("on"), 3)))
        runCurrent()

        assertEquals(7, events.size)
        assertEquals(ConnectionEvent.Opened(1), events[0])
        assertEquals(ConnectionEvent.Received(RuntimePush.ObjectDeleted("lamp", 1)), events[1])
        assertEquals(ViolationKind.UNDECODABLE_FRAME, assertIs<ConnectionEvent.Violated>(events[2]).violation.kind)
        assertEquals(ViolationKind.BINARY_FRAME, assertIs<ConnectionEvent.Violated>(events[3]).violation.kind)
        assertEquals(ViolationKind.ID_MISMATCH, assertIs<ConnectionEvent.Violated>(events[4]).violation.kind)
        assertEquals("switch", assertIs<RuntimePush.ObjectChanged>(assertIs<ConnectionEvent.Received>(events[5]).push).objectId)
        assertEquals(ConnectionEvent.Received(RuntimePush.StateChanged("lamp", listOf("on"), 3)), events[6])
        assertEquals(emptyList(), link.closes)
        assertEquals(ConnectionState.Open(1), connection.state.value)
    }

    @Test
    fun `the reader drains the link while the collector is suspended`() = runTest {
        val connection = connection()
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<ConnectionEvent>()
        backgroundScope.launch {
            connection.events.collect { event ->
                events += event
                gate.await()
            }
        }
        connection.open()
        val link = connector.links.single()

        (1L..500L).forEach { revision -> link.push(deletion("gen_Drone_$revision", revision)) }
        runCurrent()

        assertTrue(link.drained)
        assertEquals(listOf<ConnectionEvent>(ConnectionEvent.Opened(1)), events)
        gate.complete(Unit)
        runCurrent()
        assertEquals((1L..500L).toList(), events.drop(1).map { (assertIs<ConnectionEvent.Received>(it).push as RuntimePush.ObjectDeleted).revision })
    }

    @Test
    fun `a close 1003 ends the connection with the rejection of its reason, also after pushes`() = runTest {
        val cases = listOf(
            "First message must be text" to HandshakeRejection.FirstMessageNotText,
            "Invalid auth format" to HandshakeRejection.InvalidAuthFormat,
            "Invalid credentials" to HandshakeRejection.InvalidCredentials,
            "Message too big" to HandshakeRejection.Other(1003, "Message too big"),
        )

        cases.forEachIndexed { index, (reason, rejection) ->
            val connection = connection()
            connection.open()
            val link = connector.links[index]
            link.push(deletion("lamp", 1))
            link.closeByRuntime(1003, reason)
            runCurrent()

            val end = ConnectionEnd.HandshakeRejected(rejection)
            assertEquals(ConnectionState.Ended(end), connection.state.value)
            assertEquals(
                listOf(ConnectionEvent.Opened(1), ConnectionEvent.Received(RuntimePush.ObjectDeleted("lamp", 1)), ConnectionEvent.Ended(end)),
                connection.events.toList(),
            )
        }
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(cases.size, connector.links.size)
    }

    @Test
    fun `the runtime's close at a stop ends the connection as stopped without a reconnect`() = runTest {
        val connection = connection()
        val (events, collector) = collect(connection)
        connection.open()

        connector.links.single().closeByRuntime(1000, "Environment stopped")
        runCurrent()
        advanceTimeBy(1.minutes)
        runCurrent()

        assertEquals(ConnectionState.Ended(ConnectionEnd.EnvironmentStopped), connection.state.value)
        assertEquals(ConnectionEvent.Ended(ConnectionEnd.EnvironmentStopped), events.last())
        assertTrue(collector.isCompleted)
        assertEquals(1, connector.links.size)
    }

    @Test
    fun `an interruption reconnects after the policy's delay with a fresh key, the same client, the old link closed first and the next epoch`() = runTest {
        val connection = connection(ReconnectPolicy(firstDelay = 2.seconds))
        val (events, _) = collect(connection)
        connection.open()
        val first = connector.links.single()
        first.push(deletion("lamp", 1))

        first.drop()
        runCurrent()
        val closure = LinkClosure(null, null, byClient = false)
        assertEquals(ConnectionState.Reconnecting(1, closure), connection.state.value)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(1, connector.links.size)
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(ConnectionState.Open(2), connection.state.value)
        val second = connector.links[1]
        assertEquals(listOf(handshake(2)), second.sent)
        second.push(deletion("lamp", 2))
        runCurrent()
        assertEquals(
            listOf(
                ConnectionEvent.Opened(1),
                ConnectionEvent.Received(RuntimePush.ObjectDeleted("lamp", 1)),
                ConnectionEvent.Interrupted(closure),
                ConnectionEvent.Opened(2),
                ConnectionEvent.Received(RuntimePush.ObjectDeleted("lamp", 2)),
            ),
            events,
        )
        assertEquals(
            listOf(
                "issue 1", "connect", "link 1 sent ${handshake(1)}", "link 1 dropped",
                "issue 2", "link 1 closed by the client with 1000", "connect", "link 2 sent ${handshake(2)}",
            ),
            journal.steps,
        )
    }

    @Test
    fun `failed attempts back off on virtual time and the connection gives up after the policy's limit`() = runTest {
        connector.failure().failure().failure()
        val connection = connection(ReconnectPolicy(firstDelay = 1.seconds, factor = 2.0, maxAttempts = 3))

        val opening = async { connection.open() }
        runCurrent()
        assertEquals(1, connector.connectedUrls.size)
        assertEquals(ConnectionState.Reconnecting(2, null), connection.state.value)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, connector.connectedUrls.size)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, connector.connectedUrls.size)
        advanceTimeBy(1.seconds)
        runCurrent()

        val end = ConnectionEnd.GaveUp(3, "connecting to $url failed: Connection refused")
        assertEquals(ConnectionState.Ended(end), opening.await())
        assertEquals(3, connector.connectedUrls.size)
        assertEquals(3, issued)
        assertEquals(3.seconds.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun `an open link resets the count of failed attempts`() = runTest {
        connector.failure().failure().link().failure().failure().link()
        val connection = connection(ReconnectPolicy(firstDelay = 1.seconds, maxAttempts = 3))

        assertEquals(ConnectionState.Open(1), connection.open())
        connector.links.single().closeByRuntime(1011, "Ping timeout")
        runCurrent()
        assertEquals(ConnectionState.Reconnecting(1, LinkClosure(1011, "Ping timeout", byClient = false)), connection.state.value)
        advanceTimeBy((1 + 2 + 4).seconds)
        runCurrent()

        assertEquals(ConnectionState.Open(2), connection.state.value)
        assertEquals(6, connector.connectedUrls.size)
        assertEquals(listOf(handshake(6)), connector.links[1].sent)
    }

    @Test
    fun `a handshake that cannot be sent closes its link and counts as a failed attempt`() = runTest {
        connector.link(sendFailure = TransportFailure.Interrupted("Broken pipe"))
        val connection = connection(ReconnectPolicy(firstDelay = 1.seconds))

        assertEquals(ConnectionState.Open(1), connection.open())

        assertEquals(1000, connector.links[0].closes.single().first)
        assertEquals(listOf(handshake(2)), connector.links[1].sent)
        assertEquals(1.seconds.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun `the handle's phase ends the connection when it becomes stopped or lost`() = runTest {
        val stopped = connection()
        stopped.open()
        handle.moveTo(EnvironmentPhase.Stopped)
        runCurrent()

        assertEquals(ConnectionState.Ended(ConnectionEnd.EnvironmentStopped), stopped.state.value)
        assertEquals(1000, connector.links[0].closes.single().first)

        handle.moveTo(EnvironmentPhase.Running)
        val lost = connection()
        lost.open()
        handle.moveTo(EnvironmentPhase.Lost("the runtime restarted"))
        runCurrent()

        assertEquals(ConnectionState.Ended(ConnectionEnd.EnvironmentLost("the runtime restarted")), lost.state.value)
        assertEquals(1000, connector.links[1].closes.single().first)
    }

    @Test
    fun `a key refused while the phase is uncertain is a failed attempt, a refused one of a stopped environment ends it`() = runTest {
        val uncertain = EnvironmentPhase.Uncertain(setOf(PhaseKind.ACTIVATED, PhaseKind.RUNNING), "start", Uploads.NONE)
        issuances += CallOutcome.Refused(LifecycleRefusal(LifecycleOperation.ISSUE_ACCESS_KEY, uncertain, "uncertain"))
        val retried = connection(ReconnectPolicy(firstDelay = 1.seconds))

        assertEquals(ConnectionState.Open(1), retried.open())
        assertEquals(listOf(handshake(2)), connector.links.single().sent)

        issuances += CallOutcome.Refused(LifecycleRefusal(LifecycleOperation.ISSUE_ACCESS_KEY, EnvironmentPhase.Stopped, "stopped"))
        assertEquals(ConnectionState.Ended(ConnectionEnd.EnvironmentStopped), connection().open())
        assertEquals(1, connector.links.size)
    }

    @Test
    fun `a key the runtime refuses for an unknown environment ends the connection as lost, other failures are retried`() = runTest {
        val unknown = RuntimeFailure.Rejected(
            RuntimeRoute.REGISTER_CLIENT_FOR_WEB_SOCKET,
            400,
            RejectionKind.UNKNOWN_ENVIRONMENT,
            RuntimeText.UNKNOWN_ENVIRONMENT.render(mapOf("env" to environment.value)),
            mapOf("env" to environment.value),
        )
        issuances += CallOutcome.Failed(RuntimeFailure.Unreachable(RuntimeRoute.REGISTER_CLIENT_FOR_WEB_SOCKET, "Connection refused"))
        issuances += CallOutcome.Failed(unknown)

        val end = connection(ReconnectPolicy(firstDelay = 1.seconds)).open()

        assertEquals(ConnectionState.Ended(ConnectionEnd.EnvironmentLost(unknown.describe())), end)
        assertEquals(2, issued)
        assertEquals(emptyList(), connector.connectedUrls)
    }

    @Test
    fun `disconnect closes the link normally and ends the connection without a reconnect`() = runTest {
        val connection = connection()
        val (events, collector) = collect(connection)
        connection.open()
        val link = connector.links.single()

        connection.disconnect()
        advanceTimeBy(1.minutes)
        runCurrent()

        assertEquals(1000, link.closes.single().first)
        assertEquals(ConnectionState.Ended(ConnectionEnd.Disconnected), connection.state.value)
        assertEquals(listOf(ConnectionEvent.Opened(1), ConnectionEvent.Ended(ConnectionEnd.Disconnected)), events)
        assertTrue(collector.isCompleted)
        assertEquals(1, connector.links.size)
        assertEquals(ConnectionState.Ended(ConnectionEnd.Disconnected), connection.open())
    }

    @Test
    fun `close ends the connection at once, closes the link and may be called again`() = runTest {
        val connection = connection()
        connection.open()
        val link = connector.links.single()

        connection.close()
        assertEquals(ConnectionState.Ended(ConnectionEnd.Disconnected), connection.state.value)
        connection.close()
        runCurrent()

        assertEquals(1000, link.closes.single().first)
        assertEquals(ConnectionState.Ended(ConnectionEnd.Disconnected), connection.open())
        assertEquals(1, connector.links.size)
    }

    @Test
    fun `a connection closed before it opened never connects`() = runTest {
        val connection = connection()

        connection.close()

        assertEquals(ConnectionState.Ended(ConnectionEnd.Disconnected), connection.open())
        assertEquals(listOf(ConnectionEvent.Ended(ConnectionEnd.Disconnected)), connection.events.toList())
        assertEquals(emptyList(), connector.connectedUrls)
        assertEquals(0, issued)
    }

    @Test
    fun `a connection whose scope was cancelled before it opened ends as disconnected and never connects`() = runTest {
        val cancelledBefore = Job(backgroundScope.coroutineContext[Job]).apply { cancel() }
        val createdInCancelled = connection(parent = backgroundScope + cancelledBefore)
        val cancelledAfter = Job(backgroundScope.coroutineContext[Job])
        val cancelledAfterCreation = connection(parent = backgroundScope + cancelledAfter)
        cancelledAfter.cancel()

        listOf(createdInCancelled, cancelledAfterCreation).forEach { connection ->
            assertEquals(ConnectionState.Ended(ConnectionEnd.Disconnected), withTimeout(1.minutes) { connection.open() })
            assertEquals(listOf(ConnectionEvent.Ended(ConnectionEnd.Disconnected)), withTimeout(1.minutes) { connection.events.toList() })
        }
        assertEquals(emptyList(), connector.connectedUrls)
        assertEquals(0, issued)
    }

    @Test
    fun `awaitOpen answers the open or ended state at once and any other state when its bound passed`() = runTest {
        connector.hang()
        val hanging = connection()
        backgroundScope.launch { hanging.open() }
        runCurrent()
        val begin = testScheduler.currentTime

        assertEquals(ConnectionState.Connecting(1), hanging.awaitOpen(5.seconds))
        assertEquals(5.seconds.inWholeMilliseconds, testScheduler.currentTime - begin)

        val open = connection()
        open.open()
        val openedAt = testScheduler.currentTime
        assertEquals(ConnectionState.Open(1), open.awaitOpen(5.seconds))
        open.close()
        assertEquals(ConnectionState.Ended(ConnectionEnd.Disconnected), open.awaitOpen(5.seconds))
        assertEquals(openedAt, testScheduler.currentTime)
    }

    @Test
    fun `send checks the phase's verdict, then the state, and writes the encoded frame`() = runTest {
        val connection = connection()
        val frame = EventFrame("public", "start", EventPayload.None)

        handle.moveTo(EnvironmentPhase.Configuring(Uploads.NONE))
        val refusal = assertIs<SendOutcome.NotAllowed>(connection.send(frame)).refusal
        assertEquals(LifecycleOperation.INJECT_EVENT, refusal.operation)
        handle.moveTo(EnvironmentPhase.Activated)
        assertEquals(SendOutcome.NotConnected(ConnectionState.Idle), connection.send(frame))

        connection.open()
        val link = connector.links.single()
        val text = """{"environmentKey":"public","messageType":"start","payload":"","payloadFormat":"none"}"""
        assertEquals(SendOutcome.Written(text), connection.send(frame))
        assertEquals(listOf(handshake(1), text), link.sent)

        link.sendFailure = TransportFailure.Interrupted("Broken pipe")
        assertEquals(SendOutcome.Failed(TransportFailure.Interrupted("Broken pipe")), connection.send(frame))
        link.drop()
        runCurrent()
        assertIs<SendOutcome.NotConnected>(connection.send(frame))
    }
}
