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

import io.karpfen.ide.runtime.protocol.api.RejectionClassifier
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.conformance.RawRuntime.Companion.BAD_REQUEST
import io.karpfen.ide.runtime.protocol.conformance.RawRuntime.Companion.CLIENT_ID
import io.karpfen.ide.runtime.protocol.conformance.RawRuntime.Companion.CONFLICT
import io.karpfen.ide.runtime.protocol.conformance.RawRuntime.Companion.ENV_KEY
import io.karpfen.ide.runtime.protocol.conformance.RawRuntime.Companion.OK
import io.karpfen.ide.runtime.protocol.conformance.RawRuntime.Companion.lamp
import io.karpfen.ide.runtime.protocol.conformance.RawRuntime.Companion.string
import io.karpfen.ide.runtime.protocol.golden.RuntimeGoldens
import io.karpfen.ide.runtime.protocol.golden.RuntimeWireWriter
import io.karpfen.ide.runtime.protocol.message.EngineMessages
import io.karpfen.ide.runtime.protocol.message.EngineStatus
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.EventFrameEncoder
import io.karpfen.ide.runtime.protocol.message.EventPayload
import io.karpfen.ide.runtime.protocol.message.JsonReading
import io.karpfen.ide.runtime.protocol.message.RuntimeJson
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.protocol.message.WireValue
import io.karpfen.ide.runtime.protocol.transport.LinkClosure
import io.karpfen.ide.runtime.protocol.transport.LinkSendResult
import io.karpfen.ide.runtime.protocol.transport.ktor.RawWebSocketProbe
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the runtime's contract with the client. The scenarios drive the runtime only through the transports, with raw
 * requests built from the catalogue's routes, with raw links and with the lamp's texts. They cover these facts.
 * - The missing-parameter texts come in the order in which each route checks its parameters.
 * - Every route rejects an absent environment.
 * - Every route behaves in a configuring, an activated, a running and a stopped environment with the exact statuses
 *   and texts of the catalogue. A state forced in a running environment is applied at its next tick.
 * - Uploads the runtime refuses leave the environment configuring. These are an unknown type, an attachment to a
 *   missing object and a reference loop. A model with an unknown type is refused with 400 and the body that lists its
 *   one error with its line, column and code, and the environment stays without a model.
 * - Environment keys are unique and access keys are single-use, both in the runtime's shapes.
 * - The handshake is silent, and it has three refusals.
 * - The snapshot has its member order and the revision 0 before the start. Its roots are written like `objectChanged`
 *   values and equal the lamp's golden.
 * - A model subscriber receives every observation from the start with consecutive revisions.
 * - A second activation is refused, and a second start changes nothing.
 * - Domain events have the members the decoder reads.
 * - While `TickByTick` is on, the engine's status is pushed as `engineStatus` to a client that subscribed to nothing,
 *   once when the engine thread starts to wait and once when it goes on, for a pause, a tick and a resume.
 * - Pushes for a client without a session are dropped.
 * - A stop closes the sockets with `Environment stopped`. After that the environment stays readable, and a start runs
 *   it again.
 * - Features are switched on and off, listed and sent messages while configuring, running and stopped, and their
 *   refusals have the catalogue's texts.
 *
 * The scenarios run only against a real runtime, in [LiveRuntimeContractTest]. Failures that only a fake can produce
 * are ordinary tests of the client against the fake runtime. Every scenario stops the environments it launched when it
 * ends, also when it fails.
 */
abstract class RuntimeContract {

    /** The runtime the scenarios run against. */
    protected abstract val runtime: RuntimeUnderTest

    /** Where that runtime listens, for the raw links. */
    protected abstract val endpoint: RuntimeEndpoint

    @Test
    fun `every route refuses a missing parameter with the text of the first one it checks`() = scenario {
        RuntimeRoute.entries.filter { it.parameters.isNotEmpty() }.forEach { route ->
            route.parameters.indices.filter { route.parameters[it] !in route.optional }.forEach { present ->
                val given = route.parameters.take(present).map { it to sampleValue(it, "env-1") }
                val missing = route.parameters[present]
                val text = if (missing in NUMERIC) RuntimeText.INVALID_PARAMETER else RuntimeText.MISSING_PARAMETER
                rejected(BAD_REQUEST, text, mapOf("name" to missing), route, *given.toTypedArray())
            }
        }
        rejected(
            BAD_REQUEST,
            RuntimeText.INVALID_PARAMETER,
            mapOf("name" to "milliseconds"),
            RuntimeRoute.SET_TICK_DELAY,
            ENV_KEY to "env-1",
            "milliseconds" to "fast",
        )
        rejected(
            BAD_REQUEST,
            RuntimeText.INVALID_PARAMETER,
            mapOf("name" to "ttlMs"),
            RuntimeRoute.SET_EVENT_TTL,
            ENV_KEY to "env-1",
            "ttlMs" to "1.5",
        )
    }

    @Test
    fun `every route of an environment rejects an absent one`() = scenario {
        RuntimeRoute.entries.filter { ENV_KEY in it.parameters }.forEach { route ->
            val parameters = route.parameters.map { it to sampleValue(it, ABSENT) }
            rejected(
                BAD_REQUEST,
                RuntimeText.UNKNOWN_ENVIRONMENT,
                mapOf("env" to ABSENT),
                route,
                *parameters.toTypedArray(),
                upload = lamp(LAMP_FILES.metamodel),
            )
        }
    }

    @Test
    fun `features are switched, listed and sent messages in every phase with the catalogue's texts`() = scenario {
        val environment = create()
        val env = mapOf("env" to environment)
        val registered = call(RuntimeRoute.GET_REGISTERED_FEATURES)
        assertEquals(OK, registered.status, registered.body)
        val names = registered.body.split(WireNames.FEATURE_SEPARATOR)
        assertTrue(names.containsAll(listOf("History", "TickLimit", "TickByTick")), registered.body)

        expect(OK, "", RuntimeRoute.GET_ACTIVE_FEATURES, ENV_KEY to environment)
        ok(RuntimeRoute.ACTIVATE_FEATURE, ENV_KEY to environment, FEATURE_NAME to "History")
        expect(OK, "History", RuntimeRoute.GET_ACTIVE_FEATURES, ENV_KEY to environment)
        rejected(
            BAD_REQUEST, RuntimeText.FEATURE_ALREADY_ACTIVE, env + ("feature" to "History"),
            RuntimeRoute.ACTIVATE_FEATURE, ENV_KEY to environment, FEATURE_NAME to "History",
        )
        rejected(
            BAD_REQUEST, RuntimeText.UNKNOWN_FEATURE, mapOf("feature" to "Histroy"),
            RuntimeRoute.ACTIVATE_FEATURE, ENV_KEY to environment, FEATURE_NAME to "Histroy",
        )
        rejected(
            BAD_REQUEST, RuntimeText.FEATURE_NOT_ACTIVE, env + ("feature" to "TickLimit"),
            RuntimeRoute.DEACTIVATE_FEATURE, ENV_KEY to environment, FEATURE_NAME to "TickLimit",
        )
        rejected(
            BAD_REQUEST, RuntimeText.FEATURE_NOT_ACTIVATED, env + ("feature" to "TickByTick"),
            RuntimeRoute.SEND_MESSAGE_TO_FEATURE, ENV_KEY to environment, FEATURE_NAME to "TickByTick", upload = "pause",
        )

        uploadLamp(environment)
        ok(RuntimeRoute.RUN_ENVIRONMENT, ENV_KEY to environment)
        ok(RuntimeRoute.START_ENVIRONMENT, ENV_KEY to environment)
        ok(RuntimeRoute.ACTIVATE_FEATURE, ENV_KEY to environment, FEATURE_NAME to "TickByTick")
        expect(OK, "execution paused", RuntimeRoute.SEND_MESSAGE_TO_FEATURE, ENV_KEY to environment, FEATURE_NAME to "TickByTick", upload = "pause")
        ok(RuntimeRoute.DEACTIVATE_FEATURE, ENV_KEY to environment, FEATURE_NAME to "TickByTick")
        ok(RuntimeRoute.STOP_ENVIRONMENT, ENV_KEY to environment)

        ok(RuntimeRoute.DEACTIVATE_FEATURE, ENV_KEY to environment, FEATURE_NAME to "History")
        expect(OK, "", RuntimeRoute.GET_ACTIVE_FEATURES, ENV_KEY to environment)
    }

    @Test
    fun `environment keys are unique and access keys are single-use keys of the runtime's shape`() = scenario {
        val keys = List(KEYS) { create() }
        val client = clientId()
        val accessKeys = listOf(accessKey(client, keys.first()), accessKey(client, keys.first()))
        val observatoryKey = call(
            RuntimeRoute.REGISTER_OBSERVATORY_CLIENT,
            CLIENT_ID to client,
            ENV_KEY to keys.first(),
            "modelElement" to "*",
        )

        assertTrue(keys.all { ENVIRONMENT_KEY.matches(it) }, "$keys")
        assertEquals(KEYS, keys.toSet().size, "every key once: $keys")
        assertTrue((accessKeys + observatoryKey.body).all { accessKeyPattern(client).matches(it) }, "$accessKeys $observatoryKey")
        assertEquals(2, accessKeys.toSet().size)
        val used = listOf(client, keys.first(), accessKeys.first()).joinToString(WireNames.HANDSHAKE_SEPARATOR)
        connect(used)
        subscribe(client, keys.first())
        assertEquals(
            LinkClosure(WireNames.CLOSE_CANNOT_ACCEPT, WireNames.REASON_INVALID_CREDENTIALS, byClient = false),
            withTimeout(scope.patience.reaction) { connect(used).link.closure.await() },
        )
    }

    @Test
    fun `the handshake is refused with its three reasons`() = scenario {
        val environment = create()

        val noFormat = connect("a:b").link
        val foreign = connect(listOf(clientId(), environment, "ak-nobody-1-1").joinToString(WireNames.HANDSHAKE_SEPARATOR)).link
        val binary = RawWebSocketProbe.closureAfterBinaryFirstFrame(endpoint.webSocketUrl, scope.patience.reaction)

        withTimeout(scope.patience.reaction) {
            assertEquals(
                LinkClosure(WireNames.CLOSE_CANNOT_ACCEPT, WireNames.REASON_INVALID_AUTH_FORMAT, byClient = false),
                noFormat.closure.await(),
            )
            assertEquals(
                LinkClosure(WireNames.CLOSE_CANNOT_ACCEPT, WireNames.REASON_INVALID_CREDENTIALS, byClient = false),
                foreign.closure.await(),
            )
        }
        assertEquals(WireNames.CLOSE_CANNOT_ACCEPT to WireNames.REASON_FIRST_MESSAGE_NOT_TEXT, binary.code to binary.reason)
    }

    @Test
    fun `a configuring environment answers every route as the catalogue says`() = scenario {
        val environment = create()
        val client = clientId()
        val env = mapOf("env" to environment)

        rejected(
            CONFLICT,
            RuntimeText.METAMODEL_REQUIRED,
            env,
            RuntimeRoute.SET_MODEL,
            ENV_KEY to environment,
            upload = lamp(LAMP_FILES.model),
        )
        rejected(CONFLICT, RuntimeText.NO_MODEL, env, RuntimeRoute.MODEL_SNAPSHOT, ENV_KEY to environment)
        rejected(CONFLICT, RuntimeText.MODELS_REQUIRED, env, RuntimeRoute.RUN_ENVIRONMENT, ENV_KEY to environment)
        rejected(CONFLICT, RuntimeText.NOT_ACTIVATED, env, RuntimeRoute.START_ENVIRONMENT, ENV_KEY to environment)
        rejected(CONFLICT, RuntimeText.NOT_ACTIVATED, env, RuntimeRoute.STOP_ENVIRONMENT, ENV_KEY to environment)
        rejected(
            CONFLICT,
            RuntimeText.NOT_ACTIVE,
            env,
            RuntimeRoute.SET_ACTIVE_STATE,
            ENV_KEY to environment,
            "modelElement" to "lamp",
            "state" to "off",
        )
        rejected(
            BAD_REQUEST, RuntimeText.NO_STATE_MACHINE_SOURCE, env + ("element" to "lamp"),
            RuntimeRoute.STATE_MACHINE_SOURCE, ENV_KEY to environment, "modelElement" to "lamp",
        )
        rejected(
            CONFLICT,
            RuntimeText.NO_SESSION,
            env + ("client" to client),
            RuntimeRoute.SUBSCRIBE_MODEL,
            ENV_KEY to environment,
            CLIENT_ID to client,
        )
        repeat(2) { ok(RuntimeRoute.UNSUBSCRIBE_MODEL, ENV_KEY to environment, CLIENT_ID to client) }

        uploadLamp(environment)
        ok(RuntimeRoute.REGISTER_OBJECT_OBSERVER, ENV_KEY to environment, CLIENT_ID to client, "objectId" to "lamp")
        ok(RuntimeRoute.REGISTER_DOMAIN_LISTENER, ENV_KEY to environment, CLIENT_ID to client, "domain" to "switch")
        val lampSource = lamp(LAMP_FILES.machines.first().file)
        expect(OK, lampSource, RuntimeRoute.STATE_MACHINE_SOURCE, ENV_KEY to environment, "modelElement" to "lamp")
        expect(
            OK,
            RuntimeWireWriter.snapshot(environment, RuntimePhase.CONFIGURING, 0, emptyMap(), listOf(RuntimeGoldens.LAMP_ROOT)),
            RuntimeRoute.MODEL_SNAPSHOT, ENV_KEY to environment,
        )
        rejected(
            CONFLICT,
            RuntimeText.NOT_ACTIVE,
            env,
            RuntimeRoute.SET_ACTIVE_STATE,
            ENV_KEY to environment,
            "modelElement" to "lamp",
            "state" to "off",
        )
        assertTrue(listed().none { it.first == environment }, "a configuring environment is not listed")
    }

    @Test
    fun `uploads the runtime refuses leave the environment configuring`() = scenario {
        val unknownType = create()
        ok(RuntimeRoute.SET_METAMODEL, ENV_KEY to unknownType, upload = lamp(LAMP_FILES.metamodel))
        val refused = call(RuntimeRoute.SET_MODEL, ENV_KEY to unknownType, body = lamp(SynthesizedModels.LAMP_UNKNOWN_TYPE))
        assertEquals(BAD_REQUEST to SynthesizedModels.LAMP_UNKNOWN_TYPE_REJECTION.render(), refused.status to refused.body)
        val classified = RejectionClassifier.classify(RuntimeRoute.SET_MODEL, refused.status, refused.body)
        assertEquals(
            RejectionKind.CONTENT_REJECTED to SynthesizedModels.LAMP_UNKNOWN_TYPE_REJECTION,
            classified.kind to classified.content,
        )
        rejected(CONFLICT, RuntimeText.NO_MODEL, mapOf("env" to unknownType), RuntimeRoute.MODEL_SNAPSHOT, ENV_KEY to unknownType)

        val ghost = create()
        uploadLampModels(ghost)
        ok(RuntimeRoute.SET_STATE_MACHINE, ENV_KEY to ghost, "attachedTo" to "ghost", upload = lamp(LAMP_FILES.machines.first().file))
        rejected(
            CONFLICT,
            RuntimeText.UNKNOWN_ATTACHMENT,
            mapOf("env" to ghost, "keys" to "ghost"),
            RuntimeRoute.RUN_ENVIRONMENT,
            ENV_KEY to ghost,
        )
        assertEquals(RuntimePhase.CONFIGURING.wireName, string(snapshot(ghost), WireNames.PHASE))

        val ring = create()
        ok(RuntimeRoute.SET_METAMODEL, ENV_KEY to ring, upload = SynthesizedModels.text(SynthesizedModel.RING, "ring.kmeta"))
        ok(RuntimeRoute.SET_MODEL, ENV_KEY to ring, upload = SynthesizedModels.text(SynthesizedModel.RING, "ring.kmodel"))
        rejected(CONFLICT, RuntimeText.REFERENCE_LOOP, mapOf("id" to "a"), RuntimeRoute.MODEL_SNAPSHOT, ENV_KEY to ring)
    }

    @Test
    fun `an activated environment refuses changes and answers the snapshot golden before its start`() = scenario {
        val environment = create()
        val client = clientId()
        uploadLamp(environment)
        ok(RuntimeRoute.RUN_ENVIRONMENT, ENV_KEY to environment)
        val env = mapOf("env" to environment)

        expect(
            OK,
            RuntimeGoldens.LAMP_SNAPSHOT_BEFORE_START.replace(RuntimeGoldens.ENVIRONMENT, environment),
            RuntimeRoute.MODEL_SNAPSHOT,
            ENV_KEY to environment,
        )
        rejected(CONFLICT, RuntimeText.ALREADY_ACTIVE, env, RuntimeRoute.RUN_ENVIRONMENT, ENV_KEY to environment)
        configurationCalls(environment, client).forEach { (route, parameters, upload) ->
            rejected(CONFLICT, RuntimeText.ALREADY_ACTIVE, env, route, *parameters.toTypedArray(), upload = upload)
        }
        rejected(
            BAD_REQUEST,
            RuntimeText.UNKNOWN_STATE,
            mapOf("state" to "nowhere", "element" to "lamp"),
            RuntimeRoute.SET_ACTIVE_STATE,
            ENV_KEY to environment,
            "modelElement" to "lamp",
            "state" to "nowhere",
        )
        rejected(
            BAD_REQUEST, RuntimeText.UNKNOWN_STATE_MACHINE, env + ("element" to "ghost"),
            RuntimeRoute.SET_ACTIVE_STATE, ENV_KEY to environment, "modelElement" to "ghost", "state" to "off",
        )
        rejected(
            CONFLICT,
            RuntimeText.NO_SESSION,
            env + ("client" to client),
            RuntimeRoute.SUBSCRIBE_MODEL,
            ENV_KEY to environment,
            CLIENT_ID to client,
        )
        connect(client, environment)
        subscribe(client, environment)
        ok(RuntimeRoute.UNSUBSCRIBE_MODEL, ENV_KEY to environment, CLIENT_ID to client)
        assertEquals(setOf("lamp", "wallSwitch"), listed().single { it.first == environment }.second.toSet())
        val source = lamp(LAMP_FILES.machines.first().file)
        expect(OK, source, RuntimeRoute.STATE_MACHINE_SOURCE, ENV_KEY to environment, "modelElement" to "lamp")
        ok(RuntimeRoute.SET_ACTIVE_STATE, ENV_KEY to environment, "modelElement" to "lamp", "state" to "full power")
        ok(RuntimeRoute.STOP_ENVIRONMENT, ENV_KEY to environment)
    }

    @Test
    fun `a running environment refuses changes and answers every other route as the catalogue says`() = scenario {
        val environment = create()
        val client = clientId()
        val stranger = clientId()
        val observatory = clientId()
        uploadLamp(environment)
        val link = connect(client, environment)
        subscribe(client, environment)
        ok(RuntimeRoute.RUN_ENVIRONMENT, ENV_KEY to environment)
        ok(RuntimeRoute.START_ENVIRONMENT, ENV_KEY to environment)
        scope.eventually("the first stacks of both lamp machines") {
            link.stacks("lamp").firstOrNull()?.let { link.stacks("wallSwitch").firstOrNull() }
        }
        val env = mapOf("env" to environment)

        rejected(CONFLICT, RuntimeText.ALREADY_ACTIVE, env, RuntimeRoute.RUN_ENVIRONMENT, ENV_KEY to environment)
        configurationCalls(environment, client).forEach { (route, parameters, upload) ->
            rejected(CONFLICT, RuntimeText.ALREADY_ACTIVE, env, route, *parameters.toTypedArray(), upload = upload)
        }
        val strangerKey = accessKey(stranger, environment)
        assertTrue(accessKeyPattern(stranger).matches(strangerKey), strangerKey)
        val observatoryKey = call(
            RuntimeRoute.REGISTER_OBSERVATORY_CLIENT,
            CLIENT_ID to observatory,
            ENV_KEY to environment,
            "modelElement" to "*",
        )
        assertEquals(OK, observatoryKey.status, observatoryKey.body)
        assertTrue(accessKeyPattern(observatory).matches(observatoryKey.body), observatoryKey.body)
        val source = lamp(LAMP_FILES.machines.first().file)
        expect(OK, source, RuntimeRoute.STATE_MACHINE_SOURCE, ENV_KEY to environment, "modelElement" to "lamp")
        assertEquals(RuntimePhase.RUNNING.wireName, string(snapshot(environment), WireNames.PHASE))
        ok(RuntimeRoute.SUBSCRIBE_MODEL, ENV_KEY to environment, CLIENT_ID to client)
        rejected(
            CONFLICT,
            RuntimeText.NO_SESSION,
            env + ("client" to stranger),
            RuntimeRoute.SUBSCRIBE_MODEL,
            ENV_KEY to environment,
            CLIENT_ID to stranger,
        )
        ok(RuntimeRoute.START_ENVIRONMENT, ENV_KEY to environment)
        ok(RuntimeRoute.SET_ACTIVE_STATE, ENV_KEY to environment, "modelElement" to "lamp", "state" to "full power")
        scope.eventually("the forced state at the next tick") {
            assertEquals(listOf("on", "full power"), link.stacks("lamp").last())
            Unit
        }
        ok(RuntimeRoute.UNSUBSCRIBE_MODEL, ENV_KEY to environment, CLIENT_ID to client)
        ok(RuntimeRoute.STOP_ENVIRONMENT, ENV_KEY to environment)
    }

    @Test
    fun `a model subscriber receives every observation from the start with consecutive revisions and nothing before them`() = scenario {
        val environment = create()
        val client = clientId()
        uploadLamp(environment)
        val link = connect(client, environment)
        subscribe(client, environment)
        ok(RuntimeRoute.RUN_ENVIRONMENT, ENV_KEY to environment)
        ok(RuntimeRoute.START_ENVIRONMENT, ENV_KEY to environment)

        scope.eventually("the first stacks of both lamp machines") {
            assertEquals(listOf(listOf("off")), link.stacks("lamp"))
            assertEquals(listOf(listOf("down")), link.stacks("wallSwitch"))
            Unit
        }
        val firstEnvelope = link.envelopes.first()
        assertTrue(
            string(firstEnvelope, WireNames.MESSAGE_TYPE) in OBSERVATIONS,
            "the first frame is a push, no acknowledgement: $firstEnvelope",
        )
        val settled = scope.eventually("a snapshot at the revision the subscriber received last") {
            val snapshot = snapshot(environment)
            assertEquals(RuntimePhase.RUNNING.wireName, string(snapshot, WireNames.PHASE))
            assertEquals(link.revisions.maxOrNull()?.toDouble(), (snapshot[WireNames.REVISION] as WireValue.WireNumber).value)
            snapshot
        }
        assertEquals((1L..link.revisions.size).toList(), link.revisions, "every revision from the first, once and in order")
        assertEquals(
            json("""{"lamp":["off"],"wallSwitch":["down"]}""").fields.sortedBy { it.name },
            (settled[WireNames.ACTIVE_STATES] as WireValue.WireObject).fields.sortedBy { it.name },
        )
        val root = (settled[WireNames.ROOTS] as WireValue.WireArray).items.single() as WireValue.WireObject
        assertEquals(link.values("lamp").last(), root["lamp"], "the snapshot's roots are written like objectChanged values")

        ok(RuntimeRoute.START_ENVIRONMENT, ENV_KEY to environment)
        val before = link.revisions.size
        assertEquals(LinkSendResult.Sent, link.link.send(PRESS))
        scope.eventually("the reaction to a press") {
            assertEquals(listOf("up"), link.stacks("wallSwitch").last())
            assertEquals(listOf("on"), link.stacks("lamp").last())
            assertEquals("up", string(link.values("wallSwitch").last(), "position"))
            Unit
        }
        assertEquals(
            (1L..link.revisions.size).toList(), link.revisions,
            "the second start produced no observation of its own: revisions continue from ${before} without a gap",
        )
        ok(RuntimeRoute.STOP_ENVIRONMENT, ENV_KEY to environment)
    }

    @Test
    fun `a domain listener receives every published event with the members the decoder reads`() = scenario {
        val environment = create()
        val listener = clientId()
        uploadLamp(environment)
        ok(RuntimeRoute.REGISTER_DOMAIN_LISTENER, ENV_KEY to environment, CLIENT_ID to listener, "domain" to "switch")
        ok(RuntimeRoute.RUN_ENVIRONMENT, ENV_KEY to environment)
        val link = connect(listener, environment)
        subscribe(listener, environment)
        ok(RuntimeRoute.UNSUBSCRIBE_MODEL, ENV_KEY to environment, CLIENT_ID to listener)
        ok(RuntimeRoute.START_ENVIRONMENT, ENV_KEY to environment)

        link.link.send(PRESS)
        val event = scope.eventually("the domainEvent of the press") { link.payloads(WireNames.DOMAIN_EVENT).firstOrNull() }

        assertEquals("switch", string(event, WireNames.DOMAIN))
        val published = event[WireNames.PAYLOAD] as WireValue.WireObject
        val golden = json(RuntimeGoldens.PUBLISHED_EVENT)
        assertEquals(golden.fields.map { it.name }, published.fields.map { it.name }, "the members in org.json's order, as the golden")
        assertEquals("press", string(published, WireNames.EVENT))
        assertEquals("", string(published, WireNames.PAYLOAD))
        assertEquals(WireNames.FORMAT_NONE, string(published, WireNames.PAYLOAD_FORMAT))
        assertEquals("EXTERNAL_MESSAGE", string(published, WireNames.SOURCE))
        assertEquals(WireValue.WireNumber(EVENT_TTL_MS.toDouble()), published[WireNames.TTL_MS])
        assertTrue(published[WireNames.TIMESTAMP] is WireValue.WireNumber)
        ok(RuntimeRoute.STOP_ENVIRONMENT, ENV_KEY to environment)
    }

    @Test
    fun `an engine with TickByTick pushes its status to a client that subscribed to nothing when it waits and goes on`() = scenario {
        val environment = create()
        val client = clientId()
        uploadLamp(environment)
        ok(RuntimeRoute.RUN_ENVIRONMENT, ENV_KEY to environment)
        val link = connect(client, environment)
        subscribe(client, environment)
        ok(RuntimeRoute.UNSUBSCRIBE_MODEL, ENV_KEY to environment, CLIENT_ID to client)
        ok(RuntimeRoute.START_ENVIRONMENT, ENV_KEY to environment)
        ok(RuntimeRoute.ACTIVATE_FEATURE, ENV_KEY to environment, FEATURE_NAME to "TickByTick")

        expect(OK, "execution paused", RuntimeRoute.SEND_MESSAGE_TO_FEATURE, ENV_KEY to environment, FEATURE_NAME to "TickByTick", upload = "pause")
        scope.eventually("the status of the waiting engine") { link.statuses().takeIf { it.size == 1 } }
        val step = call(RuntimeRoute.SEND_MESSAGE_TO_FEATURE, ENV_KEY to environment, FEATURE_NAME to "TickByTick", body = EngineMessages.ticks(1))
        assertTrue(EngineMessages.advances(step.body), step.body)
        scope.eventually("the statuses of one tick") { link.statuses().takeIf { it.size == 3 } }
        expect(OK, "execution resumed", RuntimeRoute.SEND_MESSAGE_TO_FEATURE, ENV_KEY to environment, FEATURE_NAME to "TickByTick", upload = "resume")
        val statuses = scope.eventually("the status of the resumed engine") { link.statuses().takeIf { it.size == 4 } }

        assertEquals(
            listOf(
                EngineStatus(paused = true, waiting = true, halt = null),
                EngineStatus(paused = true, waiting = false, halt = null),
                EngineStatus(paused = true, waiting = true, halt = null),
                EngineStatus(paused = false, waiting = false, halt = null),
            ),
            statuses,
            "wait, go on and wait for the tick, then go on for the resume",
        )
        assertEquals(
            json(RuntimeGoldens.ENGINE_STATUS_RUNNING_PAYLOAD).fields.map { it.name },
            link.payloads(WireNames.ENGINE_STATUS).last().fields.map { it.name },
            "the members in org.json's order, as the golden, and no revision",
        )
        assertTrue(link.revisions.isEmpty(), "a client that subscribed to nothing gets no observation: ${link.revisions}")
        ok(RuntimeRoute.DEACTIVATE_FEATURE, ENV_KEY to environment, FEATURE_NAME to "TickByTick")
        ok(RuntimeRoute.STOP_ENVIRONMENT, ENV_KEY to environment)
    }

    /** The engine statuses this link received so far, read as the decoder reads them, failing on one it cannot read. */
    private fun RawLink.statuses(): List<EngineStatus> =
        payloads(WireNames.ENGINE_STATUS).map { payload -> assertNotNull(EngineMessages.status(payload), "$payload") }

    @Test
    fun `pushes for a client without a session are dropped, not kept for its later session`() = scenario {
        val environment = create()
        val subscriber = clientId()
        val observer = clientId()
        uploadLamp(environment)
        ok(RuntimeRoute.REGISTER_OBJECT_OBSERVER, ENV_KEY to environment, CLIENT_ID to observer, "objectId" to "lamp")
        val link = connect(subscriber, environment)
        subscribe(subscriber, environment)
        ok(RuntimeRoute.RUN_ENVIRONMENT, ENV_KEY to environment)
        ok(RuntimeRoute.START_ENVIRONMENT, ENV_KEY to environment)
        link.link.send(PRESS)
        scope.eventually("the lamp turned on") { link.stacks("lamp").lastOrNull()?.takeIf { it == listOf("on") } }
        val dropped = link.revisions.max()

        val observerLink = connect(observer, environment)
        subscribe(observer, environment)
        ok(RuntimeRoute.UNSUBSCRIBE_MODEL, ENV_KEY to environment, CLIENT_ID to observer)
        link.link.send(PRESS)
        val change = scope.eventually("the observer's first change of the lamp") { observerLink.values("lamp").firstOrNull() }

        assertEquals(listOf("off"), (change["log"] as WireValue.WireArray).items.takeLast(1).map { (it as WireValue.WireString).value })
        assertTrue(
            observerLink.revisions.all { it > dropped },
            "no push up to revision $dropped reached the later session: ${observerLink.revisions}",
        )
        ok(RuntimeRoute.STOP_ENVIRONMENT, ENV_KEY to environment)
    }

    @Test
    fun `a stop closes the sockets, keeps the final model readable, and a start runs the environment again`() = scenario {
        val environment = create()
        val client = clientId()
        uploadLamp(environment)
        val link = connect(client, environment)
        subscribe(client, environment)
        ok(RuntimeRoute.RUN_ENVIRONMENT, ENV_KEY to environment)
        ok(RuntimeRoute.START_ENVIRONMENT, ENV_KEY to environment)
        scope.eventually("the lamp's first stack") { link.stacks("lamp").firstOrNull() }
        val env = mapOf("env" to environment)

        ok(RuntimeRoute.STOP_ENVIRONMENT, ENV_KEY to environment)

        assertEquals(
            LinkClosure(WireNames.CLOSE_NORMAL, WireNames.REASON_ENVIRONMENT_STOPPED, byClient = false),
            withTimeout(scope.patience.reaction) { link.link.closure.await() },
        )
        val final = snapshot(environment)
        assertEquals(RuntimePhase.STOPPED.wireName, string(final, WireNames.PHASE))
        assertEquals(
            json("""{"lamp":["off"],"wallSwitch":["down"]}""").fields.toSet(),
            (final[WireNames.ACTIVE_STATES] as WireValue.WireObject).fields.toSet(),
        )
        rejected(
            CONFLICT,
            RuntimeText.NO_SESSION,
            env + ("client" to client),
            RuntimeRoute.SUBSCRIBE_MODEL,
            ENV_KEY to environment,
            CLIENT_ID to client,
        )
        ok(RuntimeRoute.UNSUBSCRIBE_MODEL, ENV_KEY to environment, CLIENT_ID to client)
        configurationCalls(environment, client).forEach { (route, parameters, upload) ->
            rejected(CONFLICT, RuntimeText.ALREADY_ACTIVE, env, route, *parameters.toTypedArray(), upload = upload)
        }
        accessKey(client, environment)
        val switchSource = lamp(LAMP_FILES.machines.last().file)
        expect(OK, switchSource, RuntimeRoute.STATE_MACHINE_SOURCE, ENV_KEY to environment, "modelElement" to "wallSwitch")
        ok(RuntimeRoute.STOP_ENVIRONMENT, ENV_KEY to environment)
        assertTrue(listed().any { it.first == environment }, "a stopped environment stays listed")

        ok(RuntimeRoute.START_ENVIRONMENT, ENV_KEY to environment)
        scope.eventually("the restarted environment running") {
            string(snapshot(environment), WireNames.PHASE)?.takeIf { it == RuntimePhase.RUNNING.wireName }
        }
        ok(RuntimeRoute.STOP_ENVIRONMENT, ENV_KEY to environment)
        assertEquals(RuntimePhase.STOPPED.wireName, string(snapshot(environment), WireNames.PHASE))
    }

    /** Runs [body] as a scenario against [runtime] with the raw view of it, which stops what it launched at the end. */
    private fun scenario(body: suspend RawRuntime.() -> Unit) {
        runtime.runRaw(endpoint.webSocketUrl, body)
    }

    /** The environments the runtime lists, each key with its machines' model elements. */
    private suspend fun RawRuntime.listed(): List<Pair<String, List<String>>> {
        val answer = call(RuntimeRoute.LIST_ENVIRONMENTS)
        assertEquals(OK, answer.status, answer.body)
        val listing = (RuntimeJson.read(answer.body) as JsonReading.Read).value as WireValue.WireArray
        return listing.items.map { item ->
            val entry = item as WireValue.WireObject
            val elements = (entry["modelElements"] as WireValue.WireArray).items.map { (it as WireValue.WireString).value }
            string(entry, "envKey").orEmpty() to elements
        }
    }

    /**
     * The calls that configure an environment, which the runtime refuses once it is active. Each call is a route, its
     * parameters and its body.
     */
    private fun configurationCalls(environment: String, client: String): List<Triple<RuntimeRoute, List<Pair<String, String>>, String>> =
        listOf(
            Triple(RuntimeRoute.SET_METAMODEL, listOf(ENV_KEY to environment), lamp(LAMP_FILES.metamodel)),
            Triple(RuntimeRoute.SET_EVENT_DEFINITIONS, listOf(ENV_KEY to environment), lamp(checkNotNull(LAMP_FILES.events))),
            Triple(RuntimeRoute.SET_MODEL, listOf(ENV_KEY to environment), lamp(LAMP_FILES.model)),
            Triple(
                RuntimeRoute.SET_STATE_MACHINE,
                listOf(ENV_KEY to environment, "attachedTo" to "lamp"),
                lamp(LAMP_FILES.machines.first().file),
            ),
            Triple(RuntimeRoute.SET_TICK_DELAY, listOf(ENV_KEY to environment, "milliseconds" to "50"), ""),
            Triple(RuntimeRoute.SET_EVENT_TTL, listOf(ENV_KEY to environment, "ttlMs" to "3000"), ""),
            Triple(RuntimeRoute.REGISTER_OBJECT_OBSERVER, listOf(ENV_KEY to environment, CLIENT_ID to client, "objectId" to "lamp"), ""),
            Triple(RuntimeRoute.REGISTER_DOMAIN_LISTENER, listOf(ENV_KEY to environment, CLIENT_ID to client, "domain" to "switch"), ""),
        )

    private companion object {
        /** An environment key that never exists. It is the key of the first millisecond of 1970. */
        const val ABSENT = "env-1"

        /** The number of environments the key scenario creates in a row. */
        const val KEYS = 20

        /** The event time to live the lamp scenarios configure. */
        const val EVENT_TTL_MS = 30_000L

        /** The query parameter that names a feature. */
        const val FEATURE_NAME = "featureName"

        /** The parameters that must be numbers. */
        val NUMERIC = setOf("milliseconds", "ttlMs")

        /** The message types of the pushes that carry a revision. */
        val OBSERVATIONS = setOf(
            WireNames.OBJECT_CHANGED,
            WireNames.OBJECT_DELETED,
            WireNames.STATE_CHANGE,
            WireNames.STATE_MACHINE_ATTACHED,
            WireNames.STATE_MACHINE_DETACHED,
        )

        /** The shape of an environment key. */
        val ENVIRONMENT_KEY = Regex("""env-\d+""")

        /** The files of the lamp. */
        val LAMP_FILES: ModelFiles = RawRuntime.LAMP_FILES

        /** The frame that injects a `press` of the domain `switch`. */
        val PRESS: String = EventFrameEncoder.encode(EventFrame("switch", "press", EventPayload.None))

        /** The shape of an access key of [client]. */
        fun accessKeyPattern(client: String): Regex = Regex(Regex.escape("ak-$client-") + """\d+-\d{1,4}""")

        /** A value for the parameter [name] that the runtime accepts, in the environment [environment]. */
        fun sampleValue(name: String, environment: String): String =
            when (name) {
                ENV_KEY -> environment
                "milliseconds" -> "50"
                "ttlMs" -> "3000"
                "modelElement", "attachedTo", "objectId" -> "lamp"
                "state" -> "off"
                "domain" -> "switch"
                FEATURE_NAME -> "History"
                else -> "ide-live-sample"
            }

        /** The JSON object [text]. */
        fun json(text: String): WireValue.WireObject = RawRuntime.json(text)
    }
}
