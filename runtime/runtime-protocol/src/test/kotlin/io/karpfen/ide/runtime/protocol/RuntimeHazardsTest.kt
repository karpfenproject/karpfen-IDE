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
package io.karpfen.ide.runtime.protocol

import io.karpfen.ide.runtime.protocol.api.Reach
import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.api.RuntimeResult
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.connection.ConnectionEnd
import io.karpfen.ide.runtime.protocol.connection.ConnectionEvent
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.connection.ReconnectPolicy
import io.karpfen.ide.runtime.protocol.connection.RuntimeConnection
import io.karpfen.ide.runtime.protocol.fake.FakeResponse
import io.karpfen.ide.runtime.protocol.fake.FakeRuntime
import io.karpfen.ide.runtime.protocol.golden.RuntimeWireWriter
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.transport.LinkClosure
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the client against failures of a runtime that only a fake can produce on demand. Each failure is a hardcoded
 * response or link event of the fake runtime. The tests cover these cases.
 * - A half-open old link swallows pushes until it drops. After that, the new link's first push shows the revision gap.
 * - A push is lost on an open link, which shows as a gap.
 * - The runtime has no IDE support.
 * - The runtime restarted and forgot the environment.
 * - The runtime is unreachable.
 * - A consumer does not read. This never stalls the link's reader, and so it never stalls the runtime's one
 *   broadcaster.
 *
 * The real client runs on virtual time over the fake's two seams. The real client here means the gateway, the
 * environment handle and the connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeHazardsTest {

    @Test
    fun `a half-open old link swallows pushes until it drops, and the new link's first push shows the gap`() = runTest {
        val fake = FakeRuntime(backgroundScope)
        val (connection, events) = openConnection(fake)
        val oldLink = fake.links.single()

        oldLink.push(stack(1, "off"))
        oldLink.push(stack(2, "on"))
        runCurrent()
        oldLink.drop()
        advanceTimeBy(RECONNECT.delayBefore(1) + 1.seconds)
        val newLink = fake.links.last()
        newLink.push(stack(5, "off"))
        runCurrent()

        assertEquals(
            listOf(
                ConnectionEvent.Opened(1),
                received(1, "off"),
                received(2, "on"),
                ConnectionEvent.Interrupted(LinkClosure(null, null, byClient = false)),
                ConnectionEvent.Opened(2),
                received(5, "off"),
            ),
            events,
        )
        assertEquals(ConnectionState.Open(2), connection.state.value)
        assertEquals(2, fake.links.size)
        assertTrue(newLink.handshake.orEmpty().startsWith("${CLIENT.value}:env-1:"), "the same client id reconnects")
        assertNotEquals(oldLink.handshake, newLink.handshake, "a fresh key for the new link")
        connection.close()
    }

    @Test
    fun `a push lost on an open link shows as a revision gap and closes nothing`() = runTest {
        val fake = FakeRuntime(backgroundScope)
        val (connection, events) = openConnection(fake)
        val link = fake.links.single()

        listOf(1L, 2L, 4L).forEach { revision -> link.push(stack(revision, "on")) }
        runCurrent()

        val revisions = events.filterIsInstance<ConnectionEvent.Received>().map { (it.push as RuntimePush.StateChanged).revision }
        assertEquals(listOf(1L, 2L, 4L), revisions)
        assertTrue(events.none { it is ConnectionEvent.Violated }, "a gap is no protocol violation")
        assertEquals(ConnectionState.Open(1), connection.state.value)
        connection.close()
    }

    @Test
    fun `a runtime without IDE support fails the check, and its missing routes fail the calls that need them`() = runTest {
        val fake = FakeRuntime(backgroundScope)
        val client = client(fake)

        val check = assertIs<RuntimeResult.Failed>(client.checkIdeSupport())
        val environment = created(client)
        val snapshot = assertIs<CallOutcome.Failed>(environment.snapshot())
        fake.respond(RuntimeRoute.SUBSCRIBE_MODEL, FakeResponse(FakeResponse.NOT_FOUND))
        val subscribe = assertIs<CallOutcome.Failed>(environment.subscribeModel(CLIENT))

        assertEquals(RuntimeFailure.UnexpectedStatus(RuntimeRoute.MODEL_SNAPSHOT, FakeResponse.NOT_FOUND, ""), check.failure)
        assertEquals(emptyList(), fake.requests(RuntimeRoute.MODEL_SNAPSHOT).first().parameters, "the check sends no parameter")
        assertEquals(RuntimeFailure.UnexpectedStatus(RuntimeRoute.MODEL_SNAPSHOT, FakeResponse.NOT_FOUND, ""), snapshot.failure)
        assertEquals(RuntimeFailure.UnexpectedStatus(RuntimeRoute.SUBSCRIBE_MODEL, FakeResponse.NOT_FOUND, ""), subscribe.failure)
        val missing = FakeResponse.rejected(FakeResponse.BAD_REQUEST, RuntimeText.MISSING_PARAMETER, mapOf("name" to "envKey"))
        fake.respond(RuntimeRoute.MODEL_SNAPSHOT, missing)
        assertEquals(RuntimeResult.Ok(Unit), client.checkIdeSupport())
    }

    @Test
    fun `after a restart the runtime has forgotten the environment, so the handle is lost and the connection ends`() = runTest {
        val fake = FakeRuntime(backgroundScope)
        val client = client(fake)
        val environment = created(client)
        val (connection, events) = openConnection(client, environment)
        val forgotten = FakeResponse.rejected(FakeResponse.BAD_REQUEST, RuntimeText.UNKNOWN_ENVIRONMENT, mapOf("env" to "env-1"))
        fake.respond(RuntimeRoute.REGISTER_CLIENT_FOR_WEB_SOCKET, forgotten)
        fake.respond(RuntimeRoute.LIST_ENVIRONMENTS, FakeResponse.ok("[]"))

        fake.links.single().drop()
        advanceTimeBy(RECONNECT.delayBefore(1) + 1.seconds)

        val end = assertIs<ConnectionState.Ended>(connection.state.value).end
        assertIs<ConnectionEnd.EnvironmentLost>(end)
        assertEquals(ConnectionEvent.Ended(end), events.last())
        assertIs<EnvironmentPhase.Lost>(environment.phase.value)
        assertIs<CallOutcome.Refused>(environment.snapshot())
        assertEquals(RuntimeResult.Ok(emptyList()), client.activeEnvironments())
        assertEquals(RuntimeResult.Ok(Unit), client.health())
    }

    @Test
    fun `an unreachable runtime fails calls before anything was sent, and a connection gives up after its attempts`() = runTest {
        val fake = FakeRuntime(backgroundScope)
        val client = client(fake, ReconnectPolicy(maxAttempts = 3))
        val environment = created(client)
        fake.fail(RuntimeRoute.HEALTH, TransportFailure.Unreachable("Connection refused"))
        repeat(3) { fake.refuseConnect(TransportFailure.Unreachable("Connection refused")) }
        val connection = client.connection(environment, CLIENT, backgroundScope)

        val health = assertIs<RuntimeResult.Failed>(client.health())
        val start = testScheduler.currentTime
        val state = connection.open()

        assertEquals(RuntimeFailure.Unreachable(RuntimeRoute.HEALTH, "Connection refused"), health.failure)
        assertEquals(Reach.NOT_SENT, health.failure.reach)
        val end = assertIs<ConnectionEnd.GaveUp>(assertIs<ConnectionState.Ended>(state).end)
        assertEquals(3, end.attempts)
        assertTrue("Connection refused" in end.lastFailure, end.lastFailure)
        assertEquals((RECONNECT.delayBefore(1) + RECONNECT.delayBefore(2)).inWholeMilliseconds, testScheduler.currentTime - start)
        assertEquals(3, fake.connectedUrls.size)
        assertEquals(3, fake.requests(RuntimeRoute.REGISTER_CLIENT_FOR_WEB_SOCKET).size, "a fresh key for every attempt")
    }

    @Test
    fun `a consumer that does not read never stalls the link, so the runtime's broadcaster is never held up`() = runTest {
        val fake = FakeRuntime(backgroundScope)
        val client = client(fake)
        val connection = client.connection(created(client), CLIENT, backgroundScope)
        val consumerMayRead = CompletableDeferred<Unit>()
        val received = ArrayList<ConnectionEvent>()
        backgroundScope.launch {
            connection.events.collect { event ->
                received += event
                consumerMayRead.await()
            }
        }
        connection.open()
        val link = fake.links.single()

        repeat(PUSHES) { link.push(stack(it + 1L, "on")) }
        runCurrent()

        assertTrue(link.drained, "the reader took every frame while the consumer was stuck at its first event")
        assertEquals(1, received.size)
        consumerMayRead.complete(Unit)
        runCurrent()
        assertEquals(
            (1L..PUSHES).toList(),
            received.filterIsInstance<ConnectionEvent.Received>().map { (it.push as RuntimePush.StateChanged).revision },
        )
        connection.close()
    }

    /** A client of the fake on the test's virtual time, reconnecting by [reconnect]. */
    private fun TestScope.client(fake: FakeRuntime, reconnect: ReconnectPolicy = RECONNECT): RuntimeClient =
        RuntimeClient(
            ENDPOINT,
            RuntimeClientOptions(
                dispatcher = StandardTestDispatcher(testScheduler),
                timeSource = testScheduler.timeSource,
                reconnect = reconnect,
            ),
            fake.transports(),
        )

    /** The handle of a new environment, the fake's first default key. */
    private suspend fun created(client: RuntimeClient): RuntimeEnvironment =
        assertIs<RuntimeResult.Ok<RuntimeEnvironment>>(client.createEnvironment()).value

    /**
     * An open connection of [CLIENT] to a new environment of the fake, and the events it emitted, collected as they
     * come.
     */
    private suspend fun TestScope.openConnection(fake: FakeRuntime): Pair<RuntimeConnection, List<ConnectionEvent>> {
        val client = client(fake)
        return openConnection(client, created(client))
    }

    /** An open connection of [CLIENT] to [environment] and the events it emitted, collected as they come. */
    private suspend fun TestScope.openConnection(
        client: RuntimeClient,
        environment: RuntimeEnvironment,
    ): Pair<RuntimeConnection, List<ConnectionEvent>> {
        val connection = client.connection(environment, CLIENT, backgroundScope)
        val events = ArrayList<ConnectionEvent>()
        backgroundScope.launch { connection.events.collect { events += it } }
        assertEquals(ConnectionState.Open(1), connection.open())
        runCurrent()
        return connection to events
    }

    /** The push of the lamp's stack `[state]` at [revision] to [CLIENT] of the fake's first environment. */
    private fun stack(revision: Long, state: String): String =
        RuntimeWireWriter.envelope(
            "env-1", CLIENT.value, "stateChange", RuntimeWireWriter.stateChangePayload("lamp", listOf(state), revision),
        )

    /** The event of the push [stack] of [revision] and [state]. */
    private fun received(revision: Long, state: String): ConnectionEvent =
        ConnectionEvent.Received(RuntimePush.StateChanged("lamp", listOf(state), revision))

    private companion object {
        /** The runtime the client believes it talks to. The fake answers whatever the URL is. */
        val ENDPOINT = RuntimeEndpoint.parse("http://127.0.0.1:8080")

        /** The client id of the connections. */
        val CLIENT = ClientId("karpfen-ide-4f2c9a1b7e03")

        /** The reconnect policy of the tests, the production default. */
        val RECONNECT = ReconnectPolicy()

        /** The pushes the slow consumer test sends. */
        const val PUSHES = 1_000
    }
}
