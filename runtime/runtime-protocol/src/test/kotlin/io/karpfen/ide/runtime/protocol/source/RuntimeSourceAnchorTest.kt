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
package io.karpfen.ide.runtime.protocol.source

import io.karpfen.ide.runtime.protocol.catalogue.FeatureName
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.message.HaltedTransitionKind
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.protocol.message.StateTiming
import io.karpfen.ide.runtime.protocol.message.TransitionTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the client's catalogue of the runtime against the runtime's own source. That source is the karpfen-runtime
 * submodule at the branch with IDE support. Every fact the client relies on occurs verbatim in the runtime's Kotlin
 * files. The anchored facts are these.
 * - The routes and the order in which each reads its parameters.
 * - Every rejection text as the runtime writes it.
 * - The mapping of exceptions to statuses.
 * - The WebSocket path, handshake and close reasons.
 * - The shapes of the keys.
 * - The reads of inbound event frames and the detection of their payload format.
 * - The observatory's eviction and wildcard.
 * - The members and phases of the snapshot with its wait for a tick boundary.
 * - The decisive lines of the runtime's JSON writer.
 * - The members of the `stateChange` and `domainEvent` payloads.
 * - The names of the language features and the separator of the feature lists.
 * - The names of the debugger's features, their messages and the members of the breakpoint status.
 * - The `engineStatus` push of that status to every connected client when the engine thread waits and goes on.
 *
 * A failure names the file and the snippet that no longer occurs. The texts of the runtime's older dsl-tools parser
 * are not anchored. The live runs cover them.
 *
 * Moving the karpfen-runtime submodule makes these anchors, the route completeness and the template goldens fail
 * exactly where facts changed. Then update the catalogue, the fake runtime's defaults and the runtime goldens together
 * with the client. Run the live suite against the moved runtime with `runtime/live-runtime.sh test`. Only then update
 * the anchors here.
 */
class RuntimeSourceAnchorTest {

    private val checkout = RuntimeSourceCheckout.fromSystemProperty()

    @Test
    fun `every route is declared in the runtime as the catalogue lists it`() {
        assertAnchored(
            RuntimeRoute.entries.map { route -> SourceAnchor(fileOf(route), "${route.verb.name.lowercase()}(\"${route.path}\")") },
        )
    }

    @Test
    fun `every route reads exactly its catalogue parameters in catalogue order and refuses each missing one with its text`() {
        val problems = RuntimeRoute.entries.flatMap { route ->
            val declaration = RouteDeclarations.of(checkout.text(fileOf(route))).routes
                .single { it.verb == route.verb.name && it.path == route.path }
            val texts = route.parameters.map { name -> "\"${missingText(name)}\"" }.filterNot { it in declaration.block }
            listOfNotNull("$route reads ${declaration.parameterReads}".takeIf { declaration.parameterReads != route.parameters }) +
                texts.map { "$route lacks $it" }
        }

        assertEquals(emptyList(), problems)
    }

    @Test
    fun `the two numeric parameters are converted as the catalogue assumes`() {
        assertAnchored(
            listOf(
                SourceAnchor(HTTP_ROUTES, "call.request.queryParameters[\"milliseconds\"]?.toIntOrNull()"),
                SourceAnchor(HTTP_ROUTES, "call.request.queryParameters[\"ttlMs\"]?.toLongOrNull()"),
            ),
        )
    }

    @Test
    fun `every rejection text of the catalogue is written in the runtime as its template says`() {
        assertEquals(RuntimeText.entries.toSet(), REJECTIONS.keys, "every text of the catalogue has its anchors")
        assertAnchored(REJECTIONS.values.flatten())
    }

    @Test
    fun `errors are answered with the statuses and bodies the gateway interprets`() {
        assertAnchored(
            listOf(
                SourceAnchor(HTTP_ROUTES, "is IllegalArgumentException -> HttpStatusCode.BadRequest"),
                SourceAnchor(HTTP_ROUTES, "is IllegalStateException -> HttpStatusCode.Conflict"),
                SourceAnchor(HTTP_ROUTES, "else -> HttpStatusCode.InternalServerError"),
                SourceAnchor(HTTP_ROUTES, "call.respond(status, e.message ?: \"Unexpected error\")"),
                SourceAnchor(OBSERVATION_ROUTES, "errors.respond(call, e)"),
                SourceAnchor(KTOR_SERVER, "HTTPRoutes::respondWithError"),
                SourceAnchor(
                    OBSERVATION_ROUTES,
                    "call.respondText(snapshot.toJson(), ContentType.Application.Json.withCharset(Charsets.UTF_8))",
                ),
                SourceAnchor(HTTP_ROUTES, "call.respondText(\"\"\"{\"status\":\"ok\"}\"\"\", ContentType.Application.Json)"),
            ),
        )
    }

    @Test
    fun `the WebSocket has the path, handshake, pings and closes the connection relies on`() {
        assertAnchored(
            listOf(
                SourceAnchor(KTOR_SERVER, "webSocket(\"${WireNames.WEB_SOCKET_PATH}\")"),
                SourceAnchor(KTOR_SERVER, "authMessage.split(\"${WireNames.HANDSHAKE_SEPARATOR}\")"),
                SourceAnchor(KTOR_SERVER, "if (parts.size != 3)"),
                SourceAnchor(KTOR_SERVER, "val clientId = parts[0]"),
                SourceAnchor(KTOR_SERVER, "val envKey = parts[1]"),
                SourceAnchor(KTOR_SERVER, "val accessKey = parts[2]"),
                SourceAnchor(KTOR_SERVER, closeAnchor("CANNOT_ACCEPT", WireNames.REASON_FIRST_MESSAGE_NOT_TEXT)),
                SourceAnchor(KTOR_SERVER, closeAnchor("CANNOT_ACCEPT", WireNames.REASON_INVALID_AUTH_FORMAT)),
                SourceAnchor(KTOR_SERVER, closeAnchor("CANNOT_ACCEPT", WireNames.REASON_INVALID_CREDENTIALS)),
                SourceAnchor(KTOR_SERVER, "pingPeriod = Duration.ofSeconds(60)"),
                SourceAnchor(KTOR_SERVER, "timeout = Duration.ofSeconds(15)"),
                SourceAnchor(SESSION_MANAGER, "CloseReason(CloseReason.Codes.NORMAL, \"${WireNames.REASON_ENVIRONMENT_STOPPED}\")"),
                SourceAnchor(SESSION_MANAGER, "clientSessions[environmentKey]?.values?.firstOrNull { it.clientId == clientId }"),
            ),
        )
    }

    @Test
    fun `the runtime issues environment and access keys in the shapes the client reads`() {
        assertAnchored(
            listOf(
                SourceAnchor(ENVIRONMENT_HANDLER, "newEnvironment(\"${WireNames.ENVIRONMENT_KEY_PREFIX}\$millis\")"),
                SourceAnchor(ENVIRONMENT_HANDLER, "if (envs.putIfAbsent(env.key, env) == null)"),
                SourceAnchor(ENVIRONMENT_HANDLER, "millis++"),
                SourceAnchor(
                    SESSION_MANAGER,
                    "\"${WireNames.ACCESS_KEY_PREFIX}\$clientId-\${System.currentTimeMillis()}-\${(Math.random() * 10000).toInt()}\"",
                ),
                SourceAnchor(SESSION_MANAGER, "pendingRegistrations[environmentKey]?.remove(accessKey)"),
            ),
        )
    }

    @Test
    fun `inbound event frames are read by the members the encoder writes`() {
        assertAnchored(
            listOf(
                SourceAnchor(WEB_SOCKET_MESSAGE, "obj.getString(\"${WireNames.ENVIRONMENT_KEY}\")"),
                SourceAnchor(WEB_SOCKET_MESSAGE, "obj.getString(\"${WireNames.MESSAGE_TYPE}\")"),
                SourceAnchor(WEB_SOCKET_MESSAGE, "obj.optString(\"${WireNames.PAYLOAD}\", \"\")"),
                SourceAnchor(
                    WEB_SOCKET_MESSAGE,
                    "if (obj.has(\"${WireNames.PAYLOAD_FORMAT}\")) obj.optString(\"${WireNames.PAYLOAD_FORMAT}\", null) else null",
                ),
                SourceAnchor(KTOR_SERVER, "domain = message.environmentKey"),
                SourceAnchor(KTOR_SERVER, "name = message.messageType"),
                SourceAnchor(KTOR_SERVER, "PayloadFormat.fromString(message.payloadFormat)"),
                SourceAnchor(KTOR_SERVER, "?: PayloadFormat.detect(message.payload)"),
            ),
        )
    }

    @Test
    fun `payload formats are named and detected as the encoder and the decoder assume`() {
        assertAnchored(
            listOf(
                SourceAnchor(EVENT, "when (value?.trim()?.lowercase())"),
                SourceAnchor(EVENT, "\"${WireNames.FORMAT_NONE}\", \"\" -> NONE"),
                SourceAnchor(EVENT, "\"${WireNames.FORMAT_JSON}\" -> JSON"),
                SourceAnchor(EVENT, "\"${WireNames.FORMAT_KMODEL}\" -> KMODEL"),
                SourceAnchor(EVENT, "trimmed.isEmpty() -> NONE"),
                SourceAnchor(EVENT, "trimmed.startsWith(\"{\") -> JSON"),
                SourceAnchor(EVENT, "trimmed.startsWith(\"make object\") -> KMODEL"),
                SourceAnchor(OBSERVATION_RELAY, "PayloadFormat.NONE -> \"${WireNames.FORMAT_NONE}\""),
                SourceAnchor(OBSERVATION_RELAY, "PayloadFormat.JSON -> \"${WireNames.FORMAT_JSON}\""),
                SourceAnchor(OBSERVATION_RELAY, "PayloadFormat.KMODEL -> \"${WireNames.FORMAT_KMODEL}\""),
                SourceAnchor(EVENT, "INTERNAL,"),
                SourceAnchor(EVENT, "EXTERNAL_ENGINE,"),
                SourceAnchor(EVENT, "EXTERNAL_MESSAGE"),
            ),
        )
    }

    @Test
    fun `the observatory evicts silent clients after ten seconds and the wildcard means every machine`() {
        assertAnchored(
            listOf(
                SourceAnchor(BROADCASTER, "private val clientTimeoutMs = 10_000L"),
                SourceAnchor(BROADCASTER, "sessionManager.unsubscribeFromObservatory(message.environmentKey, message.clientId)"),
                SourceAnchor(SESSION_MANAGER, "subscribedElement == \"${WireNames.EVERY_MACHINE}\" || subscribedElement == modelElementId"),
            ),
        )
    }

    @Test
    fun `the snapshot writes its members in the order the snapshot reader takes, with the phases it knows`() {
        val snapshot = checkout.text(MODEL_SNAPSHOT)
        val members = listOf(WireNames.ENVIRONMENT_KEY, WireNames.PHASE, WireNames.REVISION, WireNames.ACTIVE_STATES, WireNames.ROOTS)
        val positions = members.map { snapshot.indexOf("\\\"$it\\\":") }

        assertTrue(positions.none { it < 0 }, "members missing from $MODEL_SNAPSHOT: ${members.filterIndexed { i, _ -> positions[i] < 0 }}")
        assertEquals(positions.sorted(), positions, "the members of the snapshot are written in another order")
        assertAnchored(
            RuntimePhase.entries.map { SourceAnchor(ENVIRONMENT_PHASE, "${it.name}(\"${it.wireName}\")") } +
                listOf(
                    SourceAnchor(SNAPSHOT_SERVICE, "const val DEFAULT_TICK_WAIT_MS = 10_000L"),
                    SourceAnchor(KTOR_SERVER, "ModelSnapshotService(RegistryEnvironmentLookup, ModelSnapshotService.DEFAULT_TICK_WAIT_MS)"),
                    SourceAnchor(MODEL_SNAPSHOT, "ModelJsonWriter.quote(environmentKey)"),
                    SourceAnchor(MODEL_SNAPSHOT, "ModelJsonWriter.quote(phase.wireName)"),
                    SourceAnchor(MODEL_SNAPSHOT, "\${roots.joinToString(\",\")}"),
                    SourceAnchor(SNAPSHOT_SERVICE, "ModelSnapshot(envKey, EnvironmentPhase.CONFIGURING, 0L, emptyMap(), roots)"),
                    SourceAnchor(SNAPSHOT_SERVICE, "model.objects.map(ModelJsonWriter::write)"),
                ),
        )
    }

    @Test
    fun `the object JSON quotes, writes numbers and relations and refuses loops as the wire writer ports it`() {
        assertAnchored(
            listOf(
                """'"' -> literal.append("\\\"")""",
                """'\\' -> literal.append("\\\\")""",
                """'\b' -> literal.append("\\b")""",
                """'\u000C' -> literal.append("\\f")""",
                """'\n' -> literal.append("\\n")""",
                """'\r' -> literal.append("\\r")""",
                """'\t' -> literal.append("\\t")""",
                "if (character < ' ') {",
                """literal.append("\\u").append(character.code.toString(16).padStart(4, '0'))""",
                "if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()",
                "SimplePropertyType.BOOLEAN -> if (value as Boolean) \"true\" else \"false\"",
                "\"\${quote(key)}: \$value\"",
                "\"{\${entries.joinToString(\", \")}}\"",
                "entries.add(entry(\"${WireNames.OBJECT_ID_MEMBER}\", quote(obj.id)))",
                "entries.add(entry(\"${WireNames.TYPE_MEMBER}\", quote(obj.ofType.name)))",
                "ref.obj?.let { writeObject(it, path) } ?: \"null\"",
                "if (value != null) writeObject(value, path) else \"null\"",
                "Collections.newSetFromMap(IdentityHashMap())",
                "if (!path.add(obj)) {",
                "path.remove(obj)",
            ).map { SourceAnchor(MODEL_JSON_WRITER, it) },
        )
    }

    @Test
    fun `the stateChange and domainEvent payloads are built with the members the decoder reads`() {
        assertAnchored(
            listOf(
                ".put(\"${WireNames.MODEL_ELEMENT_ID}\", modelElementId)",
                ".put(\"${WireNames.STACK}\", JSONArray(stack))",
                ".put(\"${WireNames.REVISION}\", revision.next())",
                "sessions.notifyActiveStack(environmentKey, modelElementId, payload)",
                ".put(\"${WireNames.EVENT}\", event.name)",
                ".put(\"${WireNames.PAYLOAD}\", event.payload)",
                ".put(\"${WireNames.PAYLOAD_FORMAT}\", formatName(event.payloadFormat))",
                ".put(\"${WireNames.SOURCE}\", event.source.name)",
                ".put(\"${WireNames.TIMESTAMP}\", event.timestamp)",
                ".put(\"${WireNames.TTL_MS}\", event.ttlMs)",
                "sessions.notifyDomainEvent(environmentKey, event.domain, payload)",
                "sessions.notifyObjectChange(environmentKey, objectId, jsonValue, revision.next())",
                "sessions.notifyObjectDeleted(environmentKey, objectId, revision.next())",
            ).map { SourceAnchor(OBSERVATION_RELAY, it) },
        )
    }

    @Test
    fun `the language features are registered under the catalogue's names and listed with its separator`() {
        assertAnchored(
            listOf(
                SourceAnchor(HISTORY_FEATURE, "registryName = \"${FeatureName.HISTORY.value}\""),
                SourceAnchor(TICK_LIMIT_FEATURE, "registryName = \"${FeatureName.TICK_LIMIT.value}\""),
                SourceAnchor(HTTP_ROUTES, "featureNames.joinToString(separator = \"${WireNames.FEATURE_SEPARATOR}\")"),
                SourceAnchor(HTTP_ROUTES, "activeFeatureNames.joinToString(separator = \"${WireNames.FEATURE_SEPARATOR}\")"),
                SourceAnchor(HTTP_ROUTES, "call.respond(HttpStatusCode.OK, APIService.sendMessage(envKey, feature, message))"),
            ),
        )
    }

    @Test
    fun `the debugger's features are registered under the catalogue's names and take and answer its messages`() {
        assertAnchored(
            listOf(
                SourceAnchor(BREAKPOINT_FEATURE, "override val registryName: String = \"${FeatureName.BREAKPOINT.value}\""),
                SourceAnchor(BREAKPOINT_FEATURE, "hashSetOf(TickByTickFeature::class)"),
                SourceAnchor(TICK_BY_TICK_FEATURE, "override val registryName = \"${FeatureName.TICK_BY_TICK.value}\""),
                SourceAnchor(TICK_BY_TICK_FEATURE, "\"${WireNames.TICK_PAUSE}\" -> pause()"),
                SourceAnchor(TICK_BY_TICK_FEATURE, "\"${WireNames.TICK_RESUME}\" -> resume()"),
                SourceAnchor(TICK_BY_TICK_FEATURE, "val list = message.split(':')"),
                SourceAnchor(TICK_BY_TICK_FEATURE, "list.size == 2 && list[0] == \"${WireNames.TICK_ADVANCE_PREFIX.removeSuffix(":")}\""),
                SourceAnchor(TICK_BY_TICK_FEATURE, "return \"${WireNames.TICKS_ADVANCING}\$amount tick"),
                SourceAnchor(BREAKPOINT_FEATURE, "json.getString(\"${WireNames.BREAKPOINT_ACTION}\")"),
                SourceAnchor(BREAKPOINT_FEATURE, "\"${WireNames.BREAKPOINT_GET_STATUS}\" -> tickByTickFeature.engineStatus()"),
                SourceAnchor(TICK_BY_TICK_FEATURE, "status.put(\"${WireNames.PAUSED}\", isPaused)"),
                SourceAnchor(TICK_BY_TICK_FEATURE, "status.put(\"${WireNames.WAITING}\", isWaiting)"),
                SourceAnchor(TICK_BY_TICK_FEATURE, "halt?.let { status.put(\"${WireNames.HALT}\", it) }"),
                SourceAnchor(
                    BREAKPOINT_FEATURE,
                    "JSONObject().put(\"${WireNames.MODEL_ELEMENT_ID}\", modelElementId).put(\"${WireNames.TIMING}\", timing)" +
                        ".put(\"${WireNames.HALT_STATE}\", state.name)",
                ),
                SourceAnchor(BREAKPOINT_FEATURE, ".put(\"${WireNames.MODEL_ELEMENT_ID}\", modelElementId)"),
                SourceAnchor(BREAKPOINT_FEATURE, ".put(\"${WireNames.TIMING}\", timing)"),
                SourceAnchor(BREAKPOINT_FEATURE, ".put(\"${WireNames.HALT_KIND}\", kind)"),
                SourceAnchor(BREAKPOINT_FEATURE, ".put(\"${WireNames.HALT_FROM}\", from)"),
                SourceAnchor(BREAKPOINT_FEATURE, ".put(\"${WireNames.HALT_TO}\", to)"),
                SourceAnchor(BREAKPOINT_FEATURE, ".put(\"${WireNames.HALT_INDEX}\", index)"),
            ) +
                StateTiming.entries.map { SourceAnchor(BREAKPOINT_FEATURE, "stateHalt(modelElementId, \"${it.wireName}\", state)") } +
                TransitionTiming.entries.map { SourceAnchor(BREAKPOINT_FEATURE, "transitionHalt(modelElementId, \"${it.wireName}\", transition, index)") } +
                HaltedTransitionKind.entries.map { SourceAnchor(BREAKPOINT_FEATURE, "Triple(\"${it.wireName}\", ") },
        )
    }

    @Test
    fun `the engine status is pushed as engineStatus to every connected client when the engine waits and goes on`() {
        assertAnchored(
            listOf(
                SourceAnchor(TICK_BY_TICK_FEATURE, "onStatusChanged(engineStatus())"),
                SourceAnchor(ENGINE_FILE, "feature.onStatusChanged = reportEngineStatus"),
                SourceAnchor(ENVIRONMENT_THREAD, "engine.onEngineStatusChanged = relay::onEngineStatusChanged"),
                SourceAnchor(OBSERVATION_RELAY, "sessions.notifyEngineStatus(environmentKey, status)"),
                SourceAnchor(SESSION_MANAGER, "enqueue(environmentKey, connectedClients(environmentKey), \"${WireNames.ENGINE_STATUS}\", payload)"),
            ),
        )
    }

    /** Fails naming every anchor of [anchors] that does not hold in the checkout. */
    private fun assertAnchored(anchors: List<SourceAnchor>) {
        val missing = anchors.filterNot { it.holdsIn(checkout) }
        assertTrue(missing.isEmpty(), "The runtime at ${checkout.root} no longer contains:\n${missing.joinToString("\n")}")
    }

    /** The text with which the runtime refuses a request that lacks the parameter [name]. */
    private fun missingText(name: String): String =
        if (name in NUMERIC_PARAMETERS) {
            RuntimeText.INVALID_PARAMETER.render(mapOf("name" to name))
        } else {
            RuntimeText.MISSING_PARAMETER.render(mapOf("name" to name))
        }

    /** The close of a handshake with the Ktor close code [code] and [reason], as the runtime's handler writes it. */
    private fun closeAnchor(code: String, reason: String): String = "close(CloseReason(CloseReason.Codes.$code, \"$reason\"))"

    private companion object {
        const val SERVER = "src/main/kotlin/io/karpfen"
        const val ENGINE = "execution-engine/src/main/kotlin/io/karpfen"
        const val HTTP_ROUTES = "$SERVER/server/HTTPRoutes.kt"
        const val KTOR_SERVER = "$SERVER/server/KtorServer.kt"
        const val API_SERVICE = "$SERVER/server/APIService.kt"
        const val ENVIRONMENT_HANDLER = "$SERVER/env/EnvironmentHandler.kt"
        const val OBSERVATION_ROUTES = "$SERVER/observation/ModelObservationRoutes.kt"
        const val OBSERVATION_RELAY = "$SERVER/observation/ObservationRelay.kt"
        const val SNAPSHOT_SERVICE = "$SERVER/observation/ModelSnapshotService.kt"
        const val SUBSCRIPTION_SERVICE = "$SERVER/observation/ModelSubscriptionService.kt"
        const val MODEL_SNAPSHOT = "$SERVER/observation/ModelSnapshot.kt"
        const val ENVIRONMENT_PHASE = "$SERVER/observation/EnvironmentPhase.kt"
        const val SESSION_MANAGER = "$SERVER/websocket/ClientSessionManager.kt"
        const val WEB_SOCKET_MESSAGE = "$SERVER/websocket/WebSocketMessage.kt"
        const val BROADCASTER = "$SERVER/websocket/WebSocketBroadcaster.kt"
        const val MODEL_JSON_WRITER = "$ENGINE/exec/ModelJsonWriter.kt"
        const val EVENT = "$ENGINE/messages/Event.kt"
        const val FEATURE_MANAGER = "$ENGINE/features/FeatureManager.kt"
        const val HISTORY_FEATURE = "$ENGINE/features/language/HistoryFeature.kt"
        const val TICK_LIMIT_FEATURE = "$ENGINE/features/language/TickLimitFeature.kt"
        const val BREAKPOINT_FEATURE = "$ENGINE/features/runtime/BreakpointFeature.kt"
        const val TICK_BY_TICK_FEATURE = "$ENGINE/features/runtime/TickByTickFeature.kt"
        const val ENGINE_FILE = "execution-engine/src/main/kotlin/Engine.kt"
        const val ENVIRONMENT_THREAD = "$SERVER/env/EnvironmentThread.kt"

        /** The routes the runtime's model observation adds, declared in their own file. */
        val OBSERVATION = setOf(RuntimeRoute.MODEL_SNAPSHOT, RuntimeRoute.SUBSCRIBE_MODEL, RuntimeRoute.UNSUBSCRIBE_MODEL)

        /** The parameters that must also be numbers, refused with the text of an invalid parameter. */
        val NUMERIC_PARAMETERS = setOf("milliseconds", "ttlMs")

        /** The file that declares [route]. */
        fun fileOf(route: RuntimeRoute): String = if (route in OBSERVATION) OBSERVATION_ROUTES else HTTP_ROUTES

        /** A string literal of [text] rendered with Kotlin template expressions as the runtime writes it. */
        fun literal(text: RuntimeText, expressions: Map<String, String>): String = "\"${text.render(expressions)}\""

        /** The environment key as most runtime functions name it. */
        val ENV = mapOf("env" to "\$envKey")

        /** The feature name as the runtime's feature functions name it. */
        val FEATURE = mapOf("feature" to "\$featureName")

        /**
         * Where the runtime writes each rejection text, as a string literal whose placeholders are the Kotlin
         * expressions the runtime interpolates. The missing and invalid parameter texts are written per parameter in
         * the route blocks and anchored with them.
         */
        val REJECTIONS: Map<RuntimeText, List<SourceAnchor>> = mapOf(
            RuntimeText.MISSING_PARAMETER to listOf(SourceAnchor(OBSERVATION_ROUTES, "\"Missing required parameter: clientId\"")),
            RuntimeText.INVALID_PARAMETER to listOf(SourceAnchor(HTTP_ROUTES, "\"Missing or invalid required parameter: ttlMs\"")),
            RuntimeText.UNKNOWN_ENVIRONMENT to listOf(
                SourceAnchor(API_SERVICE, literal(RuntimeText.UNKNOWN_ENVIRONMENT, ENV)),
                SourceAnchor(SNAPSHOT_SERVICE, literal(RuntimeText.UNKNOWN_ENVIRONMENT, ENV)),
                SourceAnchor(SUBSCRIPTION_SERVICE, literal(RuntimeText.UNKNOWN_ENVIRONMENT, ENV)),
            ),
            RuntimeText.ALREADY_ACTIVE to listOf(
                SourceAnchor(API_SERVICE, literal(RuntimeText.ALREADY_ACTIVE, ENV)),
                SourceAnchor(ENVIRONMENT_HANDLER, literal(RuntimeText.ALREADY_ACTIVE, mapOf("env" to "\$key"))),
            ),
            RuntimeText.METAMODEL_REQUIRED to listOf(SourceAnchor(API_SERVICE, literal(RuntimeText.METAMODEL_REQUIRED, ENV))),
            RuntimeText.MODELS_REQUIRED to listOf(SourceAnchor(API_SERVICE, literal(RuntimeText.MODELS_REQUIRED, ENV))),
            RuntimeText.UNKNOWN_ATTACHMENT to listOf(
                SourceAnchor(API_SERVICE, literal(RuntimeText.UNKNOWN_ATTACHMENT, ENV + ("keys" to "")) + " +"),
                SourceAnchor(API_SERVICE, "missingAttachments.joinToString(\", \")"),
            ),
            RuntimeText.NOT_ACTIVATED to listOf(SourceAnchor(ENVIRONMENT_HANDLER, literal(RuntimeText.NOT_ACTIVATED, ENV))),
            RuntimeText.NO_EXECUTION_THREAD to listOf(SourceAnchor(API_SERVICE, literal(RuntimeText.NO_EXECUTION_THREAD, ENV))),
            RuntimeText.NOT_ACTIVE to listOf(SourceAnchor(API_SERVICE, literal(RuntimeText.NOT_ACTIVE, ENV))),
            RuntimeText.UNKNOWN_STATE_MACHINE to listOf(
                SourceAnchor(API_SERVICE, literal(RuntimeText.UNKNOWN_STATE_MACHINE, ENV + ("element" to "\$modelElement"))),
            ),
            RuntimeText.UNKNOWN_STATE to listOf(
                SourceAnchor(API_SERVICE, literal(RuntimeText.UNKNOWN_STATE, mapOf("state" to "\$state", "element" to "\$modelElement"))),
            ),
            RuntimeText.NO_STATE_MACHINE_SOURCE to listOf(
                SourceAnchor(API_SERVICE, literal(RuntimeText.NO_STATE_MACHINE_SOURCE, ENV + ("element" to "\$modelElement"))),
            ),
            RuntimeText.NO_MODEL to listOf(SourceAnchor(SNAPSHOT_SERVICE, literal(RuntimeText.NO_MODEL, ENV))),
            RuntimeText.TICK_BOUNDARY_TIMEOUT to listOf(
                SourceAnchor(SNAPSHOT_SERVICE, literal(RuntimeText.TICK_BOUNDARY_TIMEOUT, ENV + ("ms" to "\$tickWaitMs"))),
            ),
            RuntimeText.REFERENCE_LOOP to listOf(
                SourceAnchor(MODEL_JSON_WRITER, literal(RuntimeText.REFERENCE_LOOP, mapOf("id" to "\${obj.id}"))),
            ),
            RuntimeText.NO_SESSION to listOf(
                SourceAnchor(SUBSCRIPTION_SERVICE, literal(RuntimeText.NO_SESSION, ENV + ("client" to "\$clientId"))),
            ),
            RuntimeText.UNKNOWN_FEATURE to listOf(SourceAnchor(API_SERVICE, literal(RuntimeText.UNKNOWN_FEATURE, FEATURE))),
            RuntimeText.FEATURE_ALREADY_ACTIVE to listOf(
                SourceAnchor(API_SERVICE, literal(RuntimeText.FEATURE_ALREADY_ACTIVE, ENV + FEATURE)),
            ),
            RuntimeText.FEATURE_NOT_ACTIVE to listOf(SourceAnchor(API_SERVICE, literal(RuntimeText.FEATURE_NOT_ACTIVE, ENV + FEATURE))),
            RuntimeText.FEATURE_NOT_ACTIVATED to listOf(
                SourceAnchor(
                    API_SERVICE,
                    literal(RuntimeText.FEATURE_NOT_ACTIVATED, ENV + ("feature" to "\${FeatureRegistry.getNameByClass(featureClass)}")),
                ),
            ),
            RuntimeText.FEATURES_BUSY to listOf(SourceAnchor(FEATURE_MANAGER, literal(RuntimeText.FEATURES_BUSY, emptyMap()))),
            RuntimeText.UNEXPECTED_ERROR to listOf(SourceAnchor(HTTP_ROUTES, "e.message ?: \"${RuntimeText.UNEXPECTED_ERROR.template}\"")),
        )
    }
}
