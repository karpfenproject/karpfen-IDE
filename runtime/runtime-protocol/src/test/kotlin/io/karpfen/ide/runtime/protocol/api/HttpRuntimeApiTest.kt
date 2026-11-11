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
package io.karpfen.ide.runtime.protocol.api

import io.karpfen.ide.runtime.protocol.catalogue.AccessKey
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.HttpVerb
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.doubles.ScriptedHttp
import io.karpfen.ide.runtime.protocol.golden.RuntimeWireWriter
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.protocol.message.ViolationKind
import io.karpfen.ide.runtime.protocol.transport.TextRequest
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import io.karpfen.ide.runtime.protocol.transport.TransportPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the gateway [HttpRuntimeApi]. The request of every route is built from its catalogue entry. The tests cover
 * every kind of success body, the violations of bodies that do not fit, and the check for IDE support. They cover
 * rejections by text and status, internal errors, unexpected statuses, and transport failures by how far they got.
 * They also cover the gateway's own timeouts on virtual time and the caller's cancellation, which is always rethrown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpRuntimeApiTest {

    /** The environment of the calls. */
    private val environment = EnvironmentKey("env-1761555600000")
    /** The client of the calls that name one. */
    private val client = ClientId("karpfen-ide-4f2c9a1b7e03")
    /** The runtime's answers, scripted per test. */
    private val http = ScriptedHttp()

    /** The gateway over [http] with [timeouts], measuring on the test's virtual time. */
    private fun TestScope.api(timeouts: RuntimeTimeouts = RuntimeTimeouts()): HttpRuntimeApi =
        HttpRuntimeApi(http, timeouts, testScheduler.timeSource)

    /** The failure of [result], failing the test if it succeeded. */
    private fun <T> assertFailure(result: RuntimeResult<T>): RuntimeFailure = assertIs<RuntimeResult.Failed>(result).failure

    @Test
    fun `every route's request is built from its catalogue entry with the parameters in catalogue order`() = runTest {
        val api = api()
        val calls: List<Pair<suspend () -> Unit, TextRequest>> = listOf(
            suspend { api.setMetamodel(environment, "metamodel"); Unit } to put("/setMetamodel", listOf("envKey" to environment.value), "metamodel"),
            suspend { api.setModel(environment, "model"); Unit } to put("/setModel", listOf("envKey" to environment.value), "model"),
            suspend { api.setEventDefinitions(environment, "events"); Unit } to
                put("/setEventDefinitions", listOf("envKey" to environment.value), "events"),
            suspend { api.setStateMachine(environment, "APB 2101", "machine"); Unit } to
                put("/setStateMachine", listOf("envKey" to environment.value, "attachedTo" to "APB 2101"), "machine"),
            suspend { api.setTickDelay(environment, 50); Unit } to
                post("/setTickDelay", "envKey" to environment.value, "milliseconds" to "50"),
            suspend { api.setActiveState(environment, "living room", "full power"); Unit } to
                post("/setActiveState", "envKey" to environment.value, "modelElement" to "living room", "state" to "full power"),
            suspend { api.setEventTtl(environment, 3000); Unit } to post("/setEventTtl", "envKey" to environment.value, "ttlMs" to "3000"),
            suspend { api.registerObjectObserver(environment, client, "lamp"); Unit } to
                post("/registerObjectObserver", "envKey" to environment.value, "clientId" to client.value, "objectId" to "lamp"),
            suspend { api.registerDomainListener(environment, client, "public"); Unit } to
                post("/registerDomainListener", "envKey" to environment.value, "clientId" to client.value, "domain" to "public"),
            suspend { api.registerClientForWebSocket(client, environment); Unit } to
                post("/registerClientForWebSocket", "clientId" to client.value, "envKey" to environment.value),
            suspend { api.runEnvironment(environment); Unit } to post("/runEnvironment", "envKey" to environment.value),
            suspend { api.startEnvironment(environment); Unit } to post("/startEnvironment", "envKey" to environment.value),
            suspend { api.stopEnvironment(environment); Unit } to post("/stopEnvironment", "envKey" to environment.value),
            suspend { api.health(); Unit } to get("/health"),
            suspend { api.listEnvironments(); Unit } to get("/observatory/environments"),
            suspend { api.stateMachineSource(environment, "lamp"); Unit } to
                get("/observatory/statemachine", "envKey" to environment.value, "modelElement" to "lamp"),
            suspend { api.registerObservatoryClient(client, environment, "*"); Unit } to
                post("/observatory/registerClient", "clientId" to client.value, "envKey" to environment.value, "modelElement" to "*"),
            suspend { api.modelSnapshot(environment); Unit } to get("/observatory/model", "envKey" to environment.value),
            suspend { api.subscribeModel(environment, client); Unit } to
                post("/observatory/subscribeModel", "envKey" to environment.value, "clientId" to client.value),
            suspend { api.unsubscribeModel(environment, client); Unit } to
                post("/observatory/unsubscribeModel", "envKey" to environment.value, "clientId" to client.value),
            suspend { api.createEnvironment(); Unit } to post("/createEnvironment"),
        )

        calls.forEach { (call, _) -> call() }

        assertEquals(calls.map { it.second }, http.requests)
        assertEquals(RuntimeRoute.entries.toSet(), http.requests.map { ScriptedHttp.routeOf(it) }.toSet())
    }

    @Test
    fun `a blank parameter value is refused before anything is sent`() = runTest {
        assertFailsWith<IllegalArgumentException> { api().setStateMachine(environment, " ", "machine") }
        assertFailsWith<IllegalArgumentException> { api().registerDomainListener(environment, client, "") }
        assertEquals(emptyList(), http.requests)
    }

    @Test
    fun `success bodies are read by the route's response kind`() = runTest {
        val api = api()
        http.ok("env-1761555600001")
            .ok("ak-karpfen-ide-4f2c9a1b7e03-1761555601234-4821")
            .ok("""{"status":"ok"}""")
            .ok("""[{"modelElements":["turtle","lamp"],"envKey":"env-1","extra":1},{"envKey":"env-2","modelElements":[]}]""")
            .ok("STATEMACHINE ATTACHED TO lamp : Lamp\n")
            .ok("unexpected but harmless")

        assertEquals(RuntimeResult.Ok(EnvironmentKey("env-1761555600001")), api.createEnvironment())
        assertEquals(
            RuntimeResult.Ok(AccessKey("ak-karpfen-ide-4f2c9a1b7e03-1761555601234-4821")),
            api.registerClientForWebSocket(client, environment),
        )
        assertEquals(RuntimeResult.Ok(Unit), api.health())
        assertEquals(
            RuntimeResult.Ok(
                listOf(
                    ActiveEnvironment(EnvironmentKey("env-1"), listOf("turtle", "lamp")),
                    ActiveEnvironment(EnvironmentKey("env-2"), emptyList()),
                ),
            ),
            api.listEnvironments(),
        )
        assertEquals(RuntimeResult.Ok("STATEMACHINE ATTACHED TO lamp : Lamp\n"), api.stateMachineSource(environment, "lamp"))
        assertEquals(RuntimeResult.Ok(Unit), api.runEnvironment(environment))
    }

    @Test
    fun `a success body that does not fit the route is a violation of the protocol`() = runTest {
        val api = api()
        http.ok("environment-1")
            .ok("ak-someone-else-1761555601234-4821")
            .ok("ak-karpfen-ide-4f2c9a1b7e03-1761555601234-48211")
            .ok("""{"status":"down"}""")
            .ok("""[{"envKey":"env-1","modelElements":"turtle"}]""")
            .ok("""{"envKey":"env-1"}""")

        val failures = listOf(
            api.createEnvironment(),
            api.registerClientForWebSocket(client, environment),
            api.registerObservatoryClient(client, environment, "*"),
            api.health(),
            api.listEnvironments(),
            api.listEnvironments(),
        ).map { assertIs<RuntimeFailure.ProtocolViolated>(assertFailure(it)) }

        failures.forEach { assertEquals(ViolationKind.UNEXPECTED_BODY, it.violation.kind) }
        assertEquals("environment-1", failures.first().violation.excerpt)
    }

    @Test
    fun `a snapshot is read and must be of the requested environment`() = runTest {
        val api = api()
        val body = RuntimeWireWriter.snapshot(environment.value, RuntimePhase.HALTED, 12, mapOf("lamp" to listOf("on")), emptyList())
        http.ok(body)
            .ok(RuntimeWireWriter.snapshot("env-1", RuntimePhase.RUNNING, 1, emptyMap(), emptyList()))
            .ok("""{"environmentKey":"${environment.value}"}""")

        val snapshot = assertIs<RuntimeResult.Ok<RuntimeModelSnapshot>>(api.modelSnapshot(environment)).value
        assertEquals(RuntimePhase.HALTED, snapshot.phase)
        assertEquals(mapOf("lamp" to listOf("on")), snapshot.activeStates)
        val foreign = assertIs<RuntimeFailure.ProtocolViolated>(assertFailure(api.modelSnapshot(environment)))
        assertTrue("env-1" in foreign.violation.detail, foreign.violation.detail)
        assertIs<RuntimeFailure.ProtocolViolated>(assertFailure(api.modelSnapshot(environment)))
    }

    @Test
    fun `the runtime has IDE support exactly when the snapshot route answers its text for a missing envKey`() = runTest {
        val api = api()
        http.answer(400, "Missing required parameter: envKey")
            .answer(404, "")
            .answer(400, "Missing required parameter: modelElement")
            .answer(200, "{}")
            .answer(405, "")

        assertEquals(RuntimeResult.Ok(Unit), api.probeSnapshotRoute())
        assertEquals(RuntimeFailure.UnexpectedStatus(RuntimeRoute.MODEL_SNAPSHOT, 404, ""), assertFailure(api.probeSnapshotRoute()))
        assertEquals(RejectionKind.MISSING_PARAMETER, assertIs<RuntimeFailure.Rejected>(assertFailure(api.probeSnapshotRoute())).kind)
        assertEquals(RuntimeFailure.UnexpectedStatus(RuntimeRoute.MODEL_SNAPSHOT, 200, "{}"), assertFailure(api.probeSnapshotRoute()))
        assertIs<RuntimeFailure.UnexpectedStatus>(assertFailure(api.probeSnapshotRoute()))
        assertEquals(List(5) { get("/observatory/model") }, http.requests)
    }

    @Test
    fun `rejections become failures with the kind of their text and its placeholder values`() = runTest {
        val api = api()
        http.reject(409, "Environment with key ${environment.value} is already active and cannot be modified in the intended way.")
            .reject(400, "Environment with key  does not exist")
            .reject(409, "Unexpected error")

        val active = assertIs<RuntimeFailure.Rejected>(assertFailure(api.setTickDelay(environment, 50)))
        assertEquals(RejectionKind.ALREADY_ACTIVE, active.kind)
        assertEquals(mapOf("env" to environment.value), active.captured)
        assertEquals(409, active.status)
        assertEquals(RejectionKind.UNKNOWN_ENVIRONMENT, assertIs<RuntimeFailure.Rejected>(assertFailure(api.runEnvironment(environment))).kind)
        assertEquals(RejectionKind.UNEXPECTED_ERROR, assertIs<RuntimeFailure.Rejected>(assertFailure(api.health())).kind)
    }

    @Test
    fun `internal errors, missing paths and wrong methods are told apart from rejections`() = runTest {
        val api = api()
        http.answer(500, "Cannot invoke \"String.length()\" because \"x\" is null")
            .answer(404, "")
            .answer(405, "")

        assertEquals(
            RuntimeFailure.ServerFault(RuntimeRoute.SET_MODEL, "Cannot invoke \"String.length()\" because \"x\" is null"),
            assertFailure(api.setModel(environment, "make object")),
        )
        assertEquals(RuntimeFailure.UnexpectedStatus(RuntimeRoute.SUBSCRIBE_MODEL, 404, ""), assertFailure(api.subscribeModel(environment, client)))
        assertEquals(RuntimeFailure.UnexpectedStatus(RuntimeRoute.HEALTH, 405, ""), assertFailure(api.health()))
    }

    @Test
    fun `transport failures map by how far the request got`() = runTest {
        val api = api()
        http.fail(TransportFailure.Unreachable("Connection refused"))
            .fail(TransportFailure.TimedOut(TransportPhase.CONNECTING, "connect timeout"))
            .fail(TransportFailure.TimedOut(TransportPhase.AWAITING_RESPONSE, "request timeout"))
            .fail(TransportFailure.Interrupted("Connection reset"))

        assertEquals(RuntimeFailure.Unreachable(RuntimeRoute.HEALTH, "Connection refused"), assertFailure(api.health()))
        assertEquals(RuntimeFailure.Unreachable(RuntimeRoute.HEALTH, "connect timeout"), assertFailure(api.health()))
        assertEquals(RuntimeFailure.NoAnswer(RuntimeRoute.STOP_ENVIRONMENT, "request timeout", timedOut = true), assertFailure(api.stopEnvironment(environment)))
        assertEquals(RuntimeFailure.NoAnswer(RuntimeRoute.STOP_ENVIRONMENT, "Connection reset", timedOut = false), assertFailure(api.stopEnvironment(environment)))
    }

    @Test
    fun `a call without an answer ends after its timeout class on virtual time`() = runTest {
        val api = api(RuntimeTimeouts(quick = 10.seconds, upload = 30.seconds, stop = 20.seconds, snapshot = 25.seconds))
        http.hang().hang()

        val start = testScheduler.currentTime
        val quick = assertIs<RuntimeFailure.NoAnswer>(assertFailure(api.health()))
        val afterQuick = testScheduler.currentTime
        val snapshot = assertIs<RuntimeFailure.NoAnswer>(assertFailure(api.modelSnapshot(environment)))

        assertTrue(quick.timedOut)
        assertTrue(snapshot.timedOut)
        assertEquals(10_000, afterQuick - start)
        assertEquals(25_000, testScheduler.currentTime - afterQuick)
    }

    @Test
    fun `the caller's cancellation and the caller's own timeout are rethrown, not turned into failures`() = runTest {
        val api = api()
        http.hang().hang()

        assertFailsWith<TimeoutCancellationException> { withTimeout(2.seconds) { api.health() } }

        val call = async { api.health() }
        runCurrent()
        call.cancel()
        assertFailsWith<CancellationException> { call.await() }
        assertTrue(call.isCancelled)
    }

    /** The upload request to [path] with [query] and [body]. */
    private fun put(path: String, query: List<Pair<String, String>>, body: String) = TextRequest(HttpVerb.PUT, path, query, body)

    /** The POST request to [path] with [query]. */
    private fun post(path: String, vararg query: Pair<String, String>) = TextRequest(HttpVerb.POST, path, query.toList(), null)

    /** The GET request to [path] with [query]. */
    private fun get(path: String, vararg query: Pair<String, String>) = TextRequest(HttpVerb.GET, path, query.toList(), null)
}
