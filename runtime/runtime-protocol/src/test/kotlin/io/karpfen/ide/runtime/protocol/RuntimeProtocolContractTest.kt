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

import io.karpfen.ide.runtime.protocol.api.ActiveEnvironment
import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.api.RuntimeResult
import io.karpfen.ide.runtime.protocol.api.RuntimeTimeouts
import io.karpfen.ide.runtime.protocol.catalogue.AccessKey
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.FeatureName
import io.karpfen.ide.runtime.protocol.catalogue.HttpVerb
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.connection.AccessKeyIssuer
import io.karpfen.ide.runtime.protocol.connection.ConnectionEnd
import io.karpfen.ide.runtime.protocol.connection.ConnectionEvent
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.connection.HandshakeRejection
import io.karpfen.ide.runtime.protocol.connection.ReconnectPolicy
import io.karpfen.ide.runtime.protocol.connection.SendOutcome
import io.karpfen.ide.runtime.protocol.doubles.ScriptedConnector
import io.karpfen.ide.runtime.protocol.doubles.ScriptedHttp
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleRefusal
import io.karpfen.ide.runtime.protocol.lifecycle.Observatory
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.protocol.lifecycle.Uploads
import io.karpfen.ide.runtime.protocol.message.DecodedFrame
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.EventPayload
import io.karpfen.ide.runtime.protocol.message.JsonReading
import io.karpfen.ide.runtime.protocol.message.ProtocolViolation
import io.karpfen.ide.runtime.protocol.message.PublishedEvent
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.message.WireField
import io.karpfen.ide.runtime.protocol.message.WireValue
import io.karpfen.ide.runtime.protocol.transport.ConnectOutcome
import io.karpfen.ide.runtime.protocol.transport.HttpOutcome
import io.karpfen.ide.runtime.protocol.transport.LinkClosure
import io.karpfen.ide.runtime.protocol.transport.LinkFrame
import io.karpfen.ide.runtime.protocol.transport.LinkSendResult
import io.karpfen.ide.runtime.protocol.transport.RuntimeTransports
import io.karpfen.ide.runtime.protocol.transport.TextRequest
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import io.karpfen.ide.runtime.protocol.transport.ktor.KtorTransportOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Contract test of the public surface of the runtime protocol. The catalogue equals the runtime's 27 routes. The
 * options and policies have their documented defaults. The public values are immutable and compared by value. The
 * factory wires a client to the transports it is given. That client runs every call on the dispatcher of the options
 * and hands out handles in the phase `Configuring` with nothing uploaded. It lists the runtime's registered features.
 * It connects with keys from the handle or from a given issuer, and it closes the transports once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeProtocolContractTest {

    /** The runtime of the factory's clients. */
    private val endpoint = RuntimeEndpoint.parse("http://127.0.0.1:8080")

    /** The runtime's HTTP answers, scripted per test. */
    private val http = ScriptedHttp()

    /** The runtime's WebSocket. */
    private val connector = ScriptedConnector()

    /** How often the transports were released. */
    private var releases = 0

    /** A client from the factory over the scripted transports, running on the test's virtual time. */
    private fun TestScope.client(): RuntimeClient =
        RuntimeClient(
            endpoint,
            RuntimeClientOptions(dispatcher = StandardTestDispatcher(testScheduler), timeSource = testScheduler.timeSource),
            RuntimeTransports(http, connector) { releases++ },
        )

    /** The public value classes of the protocol, which are all the values that cross threads. */
    private val publicValues: List<Class<*>> = listOf(
        RuntimeClientOptions::class.java, RuntimeTimeouts::class.java, ReconnectPolicy::class.java, KtorTransportOptions::class.java,
        RuntimeEndpoint::class.java, TextRequest::class.java, HttpOutcome.Answered::class.java, HttpOutcome.Failed::class.java,
        TransportFailure.Unreachable::class.java, TransportFailure.Interrupted::class.java, TransportFailure.TimedOut::class.java,
        ConnectOutcome.Connected::class.java, ConnectOutcome.Failed::class.java, LinkFrame.Text::class.java,
        LinkFrame.Binary::class.java, LinkClosure::class.java, LinkSendResult.Failed::class.java,
        WireValue.WireObject::class.java, WireValue.WireArray::class.java, WireValue.WireString::class.java,
        WireValue.WireNumber::class.java, WireValue.WireBoolean::class.java, WireField::class.java,
        JsonReading.Read::class.java, JsonReading.Malformed::class.java, RuntimeModelSnapshot::class.java,
        PublishedEvent::class.java, RuntimePush.ObjectChanged::class.java, RuntimePush.ObjectDeleted::class.java,
        RuntimePush.StateChanged::class.java, RuntimePush.DomainEvent::class.java, RuntimePush.EngineStatusChanged::class.java,
        RuntimePush.Unrecognized::class.java,
        ProtocolViolation::class.java, DecodedFrame.Push::class.java, DecodedFrame.Violation::class.java,
        EventFrame::class.java, EventPayload.Json::class.java, EventPayload.Kmodel::class.java, ActiveEnvironment::class.java,
        RuntimeFailure.Unreachable::class.java, RuntimeFailure.NoAnswer::class.java, RuntimeFailure.Rejected::class.java,
        RuntimeFailure.ServerFault::class.java, RuntimeFailure.UnexpectedStatus::class.java,
        RuntimeFailure.ProtocolViolated::class.java, RuntimeResult.Ok::class.java, RuntimeResult.Failed::class.java,
        Uploads::class.java, EnvironmentPhase.Configuring::class.java, EnvironmentPhase.Uncertain::class.java,
        EnvironmentPhase.Lost::class.java, LifecycleRefusal::class.java, CallOutcome.Done::class.java,
        CallOutcome.Refused::class.java, CallOutcome.Failed::class.java, Observatory.Machine::class.java,
        HandshakeRejection.Other::class.java, ConnectionEnd.HandshakeRejected::class.java,
        ConnectionEnd.EnvironmentLost::class.java, ConnectionEnd.GaveUp::class.java, ConnectionState.Connecting::class.java,
        ConnectionState.Open::class.java, ConnectionState.Reconnecting::class.java, ConnectionState.Ended::class.java,
        ConnectionEvent.Opened::class.java, ConnectionEvent.Received::class.java, ConnectionEvent.Violated::class.java,
        ConnectionEvent.Interrupted::class.java, ConnectionEvent.Ended::class.java, SendOutcome.Written::class.java,
        SendOutcome.NotConnected::class.java, SendOutcome.NotAllowed::class.java, SendOutcome.Failed::class.java,
    )

    @Test
    fun `the catalogue equals the runtime's 27 routes`() {
        assertEquals(
            listOf(
                "POST /createEnvironment", "PUT /setMetamodel", "PUT /setModel", "PUT /setEventDefinitions",
                "PUT /setStateMachine", "POST /setTickDelay", "POST /setActiveState", "POST /setEventTtl",
                "POST /registerObjectObserver", "POST /registerDomainListener", "POST /registerClientForWebSocket",
                "POST /runEnvironment", "POST /startEnvironment", "POST /stopEnvironment", "GET /health",
                "GET /observatory/environments", "GET /observatory/statemachine", "GET /observatory/stateMachines",
                "POST /observatory/registerClient",
                "GET /getRegisteredFeatures", "POST /activateFeature", "GET /getActiveFeatures", "POST /deactivateFeature",
                "POST /sendMessageToFeature",
                "GET /observatory/model", "POST /observatory/subscribeModel", "POST /observatory/unsubscribeModel",
            ),
            RuntimeRoute.entries.map { "${it.verb} ${it.path}" },
        )
    }

    @Test
    fun `the options and policies have their documented defaults`() {
        val options = RuntimeClientOptions()

        assertEquals(Dispatchers.Default, options.dispatcher)
        assertEquals(TimeSource.Monotonic, options.timeSource)
        assertEquals(RuntimeTimeouts(quick = 10.seconds, upload = 30.seconds, stop = 30.seconds, snapshot = 30.seconds), options.timeouts)
        assertEquals(ReconnectPolicy(firstDelay = 250.milliseconds, factor = 2.0, maxDelay = 10.seconds, maxAttempts = 20), options.reconnect)
        assertEquals(
            KtorTransportOptions(connectTimeout = 5.seconds, requestTimeoutBackstop = 120.seconds, pingInterval = 20.seconds),
            KtorTransportOptions(),
        )
    }

    @Test
    fun `the public values are immutable and compared by value`() {
        val mutable = publicValues.flatMap { value ->
            value.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || Modifier.isFinal(it.modifiers) }.map { "${value.name}.${it.name}" }
        }
        assertEquals(emptyList(), mutable)

        val options = RuntimeClientOptions()
        val quicker = options.copy(timeouts = options.timeouts.copy(quick = 2.seconds))
        assertEquals(RuntimeClientOptions(), options)
        assertNotEquals(options, quicker)
        assertEquals(10.seconds, options.timeouts.quick)
        assertEquals(EventFrame("public", "start", EventPayload.None), EventFrame("public", "start", EventPayload.None))
        assertEquals(EnvironmentPhase.Configuring(Uploads.NONE), EnvironmentPhase.Configuring(Uploads.NONE.copy()))
    }

    @Test
    fun `the factory's client reaches the runtime through the given transports`() = runTest {
        val client = client()
        http.ok("""{"status":"ok"}""")
            .answer(400, "Missing required parameter: envKey")
            .ok("""[{"envKey":"env-1761555500000","modelElements":["lamp"]}]""")
            .ok("env-1761555600000")

        assertEquals(endpoint, client.endpoint)
        assertEquals(RuntimeResult.Ok(Unit), client.health())
        assertEquals(RuntimeResult.Ok(Unit), client.checkIdeSupport())
        assertEquals(
            RuntimeResult.Ok(listOf(ActiveEnvironment(EnvironmentKey("env-1761555500000"), listOf("lamp")))),
            client.activeEnvironments(),
        )
        val handle = assertIs<RuntimeResult.Ok<RuntimeEnvironment>>(client.createEnvironment()).value

        assertEquals(EnvironmentKey("env-1761555600000"), handle.key)
        assertEquals(EnvironmentPhase.Configuring(Uploads.NONE), handle.phase.value)
        assertEquals(
            listOf(
                TextRequest(HttpVerb.GET, "/health", emptyList(), null),
                TextRequest(HttpVerb.GET, "/observatory/model", emptyList(), null),
                TextRequest(HttpVerb.GET, "/observatory/environments", emptyList(), null),
                TextRequest(HttpVerb.POST, "/createEnvironment", emptyList(), null),
            ),
            http.requests,
        )
    }

    @Test
    fun `the factory's client lists the runtime's registered features`() = runTest {
        http.ok("History,TickLimit")

        assertEquals(RuntimeResult.Ok(setOf(FeatureName.HISTORY, FeatureName.TICK_LIMIT)), client().registeredFeatures())
        assertEquals(listOf(TextRequest(HttpVerb.GET, "/getRegisteredFeatures", emptyList(), null)), http.requests)
    }

    @Test
    fun `a runtime without the snapshot route lacks IDE support`() = runTest {
        http.answer(404, "")

        assertEquals(RuntimeResult.Failed(RuntimeFailure.UnexpectedStatus(RuntimeRoute.MODEL_SNAPSHOT, 404, "")), client().checkIdeSupport())
    }

    @Test
    fun `every call runs on the options' dispatcher, so its timeout runs on virtual time`() = runTest {
        http.hang()
        val begin = testScheduler.currentTime

        val failure = assertIs<RuntimeResult.Failed>(client().health()).failure

        assertTrue(assertIs<RuntimeFailure.NoAnswer>(failure).timedOut)
        assertEquals(10.seconds.inWholeMilliseconds, testScheduler.currentTime - begin)
    }

    @Test
    fun `a connection takes its keys from the handle without an observatory and connects to the endpoint's WebSocket`() = runTest {
        val client = client()
        http.ok("env-1761555600000").ok("ak-ide-4f2c-1761555601234-4821")
        val handle = assertIs<RuntimeResult.Ok<RuntimeEnvironment>>(client.createEnvironment()).value

        val connection = client.connection(handle, ClientId("ide-4f2c"), backgroundScope)

        assertEquals(EnvironmentKey("env-1761555600000"), connection.environment)
        assertEquals(ClientId("ide-4f2c"), connection.clientId)
        assertEquals(ConnectionState.Idle, connection.state.value)
        assertEquals(ConnectionState.Open(1), connection.open())
        assertEquals(listOf("ws://127.0.0.1:8080/ws"), connector.connectedUrls)
        assertEquals(listOf("ide-4f2c:env-1761555600000:ak-ide-4f2c-1761555601234-4821"), connector.links.single().sent)
        assertEquals(
            TextRequest(HttpVerb.POST, "/registerClientForWebSocket", listOf("clientId" to "ide-4f2c", "envKey" to "env-1761555600000"), null),
            http.requests.last(),
        )
        connection.close()
    }

    @Test
    fun `a connection takes its keys from a given issuer instead`() = runTest {
        val client = client()
        http.ok("env-1761555600000")
        val handle = assertIs<RuntimeResult.Ok<RuntimeEnvironment>>(client.createEnvironment()).value
        val issuer = AccessKeyIssuer { CallOutcome.Done(AccessKey("ak-ide-4f2c-1761555601234-7")) }

        val connection = client.connection(handle, ClientId("ide-4f2c"), backgroundScope, issuer)

        assertEquals(ConnectionState.Open(1), connection.open())
        assertEquals(listOf("ide-4f2c:env-1761555600000:ak-ide-4f2c-1761555601234-7"), connector.links.single().sent)
        assertEquals(listOf("/createEnvironment"), http.paths)
        connection.close()
    }

    @Test
    fun `closing the client closes the transports once and leaves the environments alone`() = runTest {
        val client = client()

        client.close()
        client.close()

        assertEquals(1, releases)
        assertEquals(emptyList(), http.requests)
    }
}
