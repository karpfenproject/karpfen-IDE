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

import io.karpfen.ide.runtime.protocol.catalogue.BodyKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.message.JsonReading
import io.karpfen.ide.runtime.protocol.message.RuntimeJson
import io.karpfen.ide.runtime.protocol.message.WireValue
import io.karpfen.ide.runtime.protocol.transport.ConnectOutcome
import io.karpfen.ide.runtime.protocol.transport.HttpOutcome
import io.karpfen.ide.runtime.protocol.transport.LinkFrame
import io.karpfen.ide.runtime.protocol.transport.LinkSendResult
import io.karpfen.ide.runtime.protocol.transport.TextRequest
import io.karpfen.ide.runtime.protocol.transport.WebSocketLink
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * Runs [body] as a scenario against this runtime with the raw view of it at [webSocketUrl], and stops every environment
 * the scenario asked to activate or start and did not stop when it ends, also when it fails, so that a failed scenario
 * leaves no engine running for the scenarios after it.
 */
internal fun RuntimeUnderTest.runRaw(webSocketUrl: String, body: suspend RawRuntime.() -> Unit) {
    run {
        val raw = RawRuntime(this, webSocketUrl)
        try {
            raw.body()
        } finally {
            withContext(NonCancellable) { raw.stopLaunched() }
        }
    }
}

/**
 * The runtime as the contract scenarios drive it. The scenarios use raw requests built from the catalogue's routes and
 * raw links, through the transports of the scenario. They bypass the client's gateway, lifecycle and connection, so
 * that they check the runtime's behaviour itself. The object remembers the environments it asked to activate or start
 * until a stop of them succeeds, so that [stopLaunched] can stop them after the scenario.
 *
 * @property scope the scenario's scope, whose transports reach the runtime and whose patience bounds every wait.
 * @property webSocketUrl the URL of the runtime's WebSocket.
 */
internal class RawRuntime(val scope: RuntimeScenarioScope, private val webSocketUrl: String) {

    /** The keys of the environments asked to activate or start and not stopped since, in that order. */
    private val launched = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Sends [route] with the query [parameters] in the order given, and with [body] for a route that takes one. Returns
     * the runtime's answer. A transport failure fails the scenario.
     */
    suspend fun call(route: RuntimeRoute, vararg parameters: Pair<String, String>, body: String = ""): HttpOutcome.Answered {
        val request = TextRequest(route.verb, route.path, parameters.toList(), body.takeIf { route.body != BodyKind.NONE })
        val environment = request.query.firstOrNull { it.first == ENV_KEY }?.second
        if (environment != null && route in LAUNCHING) launched.update { it + environment }
        val answer = when (val outcome = scope.transports.http.exchange(request)) {
            is HttpOutcome.Answered -> outcome
            is HttpOutcome.Failed -> fail("$route failed on the transport: ${outcome.failure}. ${scope.diagnostics}")
        }
        if (environment != null && route == RuntimeRoute.STOP_ENVIRONMENT && answer.status == OK) launched.update { it - environment }
        return answer
    }

    /**
     * Stops every environment asked to activate or start and not stopped since, whatever the runtime answers. Each stop
     * is bounded by the patience for a reaction. This is the cleanup after a scenario, also after one that failed.
     */
    suspend fun stopLaunched() {
        launched.value.forEach { environment ->
            val route = RuntimeRoute.STOP_ENVIRONMENT
            val stop = TextRequest(route.verb, route.path, listOf(ENV_KEY to environment), null)
            withTimeoutOrNull(scope.patience.reaction) { scope.transports.http.exchange(stop) }
        }
    }

    /** Sends [route] and asserts the answer's [status] and [body]. */
    suspend fun expect(status: Int, body: String, route: RuntimeRoute, vararg parameters: Pair<String, String>, upload: String = "") {
        val answer = call(route, *parameters, body = upload)
        assertEquals(status to body, answer.status to answer.body, "$route ${parameters.toList()}")
    }

    /** Sends [route] and asserts 200 with an empty body. */
    suspend fun ok(route: RuntimeRoute, vararg parameters: Pair<String, String>, upload: String = "") {
        expect(OK, "", route, *parameters, upload = upload)
    }

    /** Sends [route] and asserts the rejection [status] with [text] rendered with [values]. */
    suspend fun rejected(
        status: Int,
        text: RuntimeText,
        values: Map<String, String>,
        route: RuntimeRoute,
        vararg parameters: Pair<String, String>,
        upload: String = "",
    ) {
        expect(status, text.render(values), route, *parameters, upload = upload)
    }

    /** Creates an environment and returns its key. */
    suspend fun create(): String {
        val answer = call(RuntimeRoute.CREATE_ENVIRONMENT)
        assertEquals(OK, answer.status, answer.body)
        return answer.body
    }

    /** Uploads the lamp's metamodel, events and model to [environment]. */
    suspend fun uploadLampModels(environment: String) {
        ok(RuntimeRoute.SET_METAMODEL, ENV_KEY to environment, upload = lamp(LAMP_FILES.metamodel))
        ok(RuntimeRoute.SET_EVENT_DEFINITIONS, ENV_KEY to environment, upload = lamp(checkNotNull(LAMP_FILES.events)))
        ok(RuntimeRoute.SET_MODEL, ENV_KEY to environment, upload = lamp(LAMP_FILES.model))
    }

    /** Uploads the lamp's two machines to [environment]. */
    suspend fun uploadLampMachines(environment: String) {
        LAMP_FILES.machines.forEach { machine ->
            ok(RuntimeRoute.SET_STATE_MACHINE, ENV_KEY to environment, "attachedTo" to machine.attachedTo, upload = lamp(machine.file))
        }
    }

    /** Uploads the whole lamp to [environment] with a tick delay of 50 ms and an event time to live of 30 000 ms. */
    suspend fun uploadLamp(environment: String) {
        uploadLampModels(environment)
        uploadLampMachines(environment)
        ok(RuntimeRoute.SET_TICK_DELAY, ENV_KEY to environment, "milliseconds" to "50")
        ok(RuntimeRoute.SET_EVENT_TTL, ENV_KEY to environment, "ttlMs" to "30000")
    }

    /** Issues an access key for [client] in [environment] and returns it. */
    suspend fun accessKey(client: String, environment: String): String {
        val answer = call(RuntimeRoute.REGISTER_CLIENT_FOR_WEB_SOCKET, CLIENT_ID to client, ENV_KEY to environment)
        assertEquals(OK, answer.status, answer.body)
        return answer.body
    }

    /** Opens a WebSocket to the runtime and sends [handshake] as its first frame. */
    suspend fun connect(handshake: String): RawLink {
        val link = assertIs<ConnectOutcome.Connected>(scope.transports.webSockets.connect(webSocketUrl), webSocketUrl).link
        assertEquals(LinkSendResult.Sent, link.send(handshake))
        return RawLink(link, scope)
    }

    /** A fresh key for [client] in [environment] and a link authenticated with it. */
    suspend fun connect(client: String, environment: String): RawLink =
        connect(listOf(client, environment, accessKey(client, environment)).joinToString(WireNames.HANDSHAKE_SEPARATOR))

    /**
     * Subscribes [client] to the whole model of [environment] once its session is attached. The subscribe route is the
     * only proof that the runtime processed the handshake. So the call is repeated while the runtime answers that the
     * client has no session.
     */
    suspend fun subscribe(client: String, environment: String) {
        scope.eventually("an attached session of $client in $environment") {
            val answer = call(RuntimeRoute.SUBSCRIBE_MODEL, ENV_KEY to environment, CLIENT_ID to client)
            assertEquals(OK to "", answer.status to answer.body, "subscribing $client")
            Unit
        }
    }

    /** The snapshot of [environment] as JSON, failing unless the runtime answers 200. */
    suspend fun snapshot(environment: String): WireValue.WireObject = json(snapshotBody(environment))

    /** The snapshot body of [environment], failing unless the runtime answers 200. */
    suspend fun snapshotBody(environment: String): String {
        val answer = call(RuntimeRoute.MODEL_SNAPSHOT, ENV_KEY to environment)
        assertEquals(OK, answer.status, answer.body)
        return answer.body
    }

    /** A client id of the scenario that no other scenario uses: `ide-live-` and random hexadecimal digits. */
    fun clientId(): String = "ide-live-" + Random.nextLong().toULong().toString(HEX_RADIX)

    companion object {
        /** The status of a success. */
        const val OK = 200

        /** The status of a rejected argument. */
        const val BAD_REQUEST = 400

        /** The status of a rejected state. */
        const val CONFLICT = 409

        /** The query parameter of the environment key. */
        const val ENV_KEY = "envKey"

        /** The query parameter of the client id. */
        const val CLIENT_ID = "clientId"

        /** The radix of the random part of a client id. */
        const val HEX_RADIX = 16

        /** The routes that activate or start an environment, which then has to be stopped. */
        val LAUNCHING = setOf(RuntimeRoute.RUN_ENVIRONMENT, RuntimeRoute.START_ENVIRONMENT)

        /** The files of the lamp model. */
        val LAMP_FILES: ModelFiles = SynthesizedModels.files(SynthesizedModel.LAMP)

        /** The text of the lamp's file [name]. */
        fun lamp(name: String): String = SynthesizedModels.text(SynthesizedModel.LAMP, name)

        /** The JSON object [text], failing if it is none. */
        fun json(text: String): WireValue.WireObject =
            assertIs<WireValue.WireObject>(assertIs<JsonReading.Read>(RuntimeJson.read(text), text).value, text)

        /** The string of [member] in [value]. */
        fun string(value: WireValue.WireObject, member: String): String? = (value[member] as? WireValue.WireString)?.value
    }
}

/**
 * A raw WebSocket to the runtime with every text frame it received so far, in order, collected by a coroutine of the
 * scenario.
 *
 * @property link the link.
 */
internal class RawLink(val link: WebSocketLink, scope: RuntimeScenarioScope) {

    /** The texts received so far. */
    private val received = MutableStateFlow<List<String>>(emptyList())

    /** The texts received so far, in order. */
    val texts: StateFlow<List<String>> get() = received

    init {
        scope.launch {
            for (frame in link.incoming) {
                if (frame is LinkFrame.Text) received.value = received.value + frame.text
            }
        }
    }

    /** The pushes received so far as JSON envelopes. */
    val envelopes: List<WireValue.WireObject> get() = received.value.map { RawRuntime.json(it) }

    /** The payloads of the pushes of [messageType] received so far. */
    fun payloads(messageType: String): List<WireValue.WireObject> =
        envelopes.filter { RawRuntime.string(it, WireNames.MESSAGE_TYPE) == messageType }
            .map { it[WireNames.PAYLOAD] as WireValue.WireObject }

    /** The revisions of the object, stack and instance pushes received so far, in order. */
    val revisions: List<Long>
        get() = envelopes.filter { RawRuntime.string(it, WireNames.MESSAGE_TYPE) in REVISED }
            .map { ((it[WireNames.PAYLOAD] as WireValue.WireObject)[WireNames.REVISION] as WireValue.WireNumber).value.toLong() }

    /** The stacks pushed so far for the machine attached to [modelElement], in order. */
    fun stacks(modelElement: String): List<List<String>> =
        payloads(WireNames.STATE_CHANGE).filter { RawRuntime.string(it, WireNames.MODEL_ELEMENT_ID) == modelElement }
            .map { payload -> (payload[WireNames.STACK] as WireValue.WireArray).items.map { (it as WireValue.WireString).value } }

    /** The values pushed so far for the object [objectId], in order. */
    fun values(objectId: String): List<WireValue.WireObject> =
        payloads(WireNames.OBJECT_CHANGED).filter { RawRuntime.string(it, WireNames.OBJECT_ID) == objectId }
            .map { it[WireNames.VALUE] as WireValue.WireObject }

    private companion object {
        /** The message types that carry a revision. */
        val REVISED = setOf(
            WireNames.OBJECT_CHANGED,
            WireNames.OBJECT_DELETED,
            WireNames.STATE_CHANGE,
            WireNames.STATE_MACHINE_ATTACHED,
            WireNames.STATE_MACHINE_DETACHED,
        )
    }
}
