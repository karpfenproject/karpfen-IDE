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
import io.karpfen.ide.runtime.protocol.catalogue.FeatureName
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.protocol.transport.HttpOutcome
import io.karpfen.ide.runtime.protocol.transport.RuntimeHttp
import io.karpfen.ide.runtime.protocol.transport.TextRequest
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import io.karpfen.ide.runtime.protocol.transport.TransportPhase
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.time.TimeSource

/**
 * The gateway over the HTTP seam. It builds every request from the route's catalogue entry alone, with the parameters
 * in catalogue order. It sends the request through [http] within the timeout of the route's timeout class and
 * interprets the answer with [ResponseInterpreter].
 *
 * The timeout is the gateway's own. When it expires, the call becomes a [RuntimeFailure.NoAnswer] with `timedOut` set.
 * A cancellation of the caller is rethrown, and that includes an enclosing timeout. Transport failures map by how far
 * the request got. [TransportFailure.Unreachable] and a timeout while connecting become [RuntimeFailure.Unreachable],
 * because nothing was sent. A timeout while awaiting the answer and [TransportFailure.Interrupted] become
 * [RuntimeFailure.NoAnswer], because the runtime may have applied the call.
 *
 * @param http the HTTP seam.
 * @param timeouts the timeouts of the timeout classes.
 * @param timeSource the clock with which the gateway measures its calls for its debug log.
 */
internal class HttpRuntimeApi(
    private val http: RuntimeHttp,
    private val timeouts: RuntimeTimeouts,
    private val timeSource: TimeSource,
) : RuntimeApi {

    override suspend fun createEnvironment(): RuntimeResult<EnvironmentKey> =
        call(RuntimeRoute.CREATE_ENVIRONMENT, emptyList(), null, SuccessBodies.ENVIRONMENT_KEY)

    override suspend fun setMetamodel(environment: EnvironmentKey, text: String): RuntimeResult<Unit> =
        call(RuntimeRoute.SET_METAMODEL, listOf(environment.value), text, SuccessBodies.EMPTY)

    override suspend fun setModel(environment: EnvironmentKey, text: String): RuntimeResult<Unit> =
        call(RuntimeRoute.SET_MODEL, listOf(environment.value), text, SuccessBodies.EMPTY)

    override suspend fun setEventDefinitions(environment: EnvironmentKey, text: String): RuntimeResult<Unit> =
        call(RuntimeRoute.SET_EVENT_DEFINITIONS, listOf(environment.value), text, SuccessBodies.EMPTY)

    override suspend fun setStateMachine(environment: EnvironmentKey, attachedTo: String, text: String): RuntimeResult<Unit> =
        call(RuntimeRoute.SET_STATE_MACHINE, listOf(environment.value, attachedTo), text, SuccessBodies.EMPTY)

    override suspend fun setTickDelay(environment: EnvironmentKey, milliseconds: Int): RuntimeResult<Unit> =
        call(RuntimeRoute.SET_TICK_DELAY, listOf(environment.value, milliseconds.toString()), null, SuccessBodies.EMPTY)

    override suspend fun setActiveState(environment: EnvironmentKey, modelElement: String, state: String): RuntimeResult<Unit> =
        call(RuntimeRoute.SET_ACTIVE_STATE, listOf(environment.value, modelElement, state), null, SuccessBodies.EMPTY)

    override suspend fun setEventTtl(environment: EnvironmentKey, milliseconds: Long): RuntimeResult<Unit> =
        call(RuntimeRoute.SET_EVENT_TTL, listOf(environment.value, milliseconds.toString()), null, SuccessBodies.EMPTY)

    override suspend fun registerObjectObserver(environment: EnvironmentKey, client: ClientId, objectId: String): RuntimeResult<Unit> =
        call(RuntimeRoute.REGISTER_OBJECT_OBSERVER, listOf(environment.value, client.value, objectId), null, SuccessBodies.EMPTY)

    override suspend fun registerDomainListener(environment: EnvironmentKey, client: ClientId, domain: String): RuntimeResult<Unit> =
        call(RuntimeRoute.REGISTER_DOMAIN_LISTENER, listOf(environment.value, client.value, domain), null, SuccessBodies.EMPTY)

    override suspend fun registerClientForWebSocket(client: ClientId, environment: EnvironmentKey): RuntimeResult<AccessKey> =
        call(RuntimeRoute.REGISTER_CLIENT_FOR_WEB_SOCKET, listOf(client.value, environment.value), null, SuccessBodies.accessKey(client))

    override suspend fun runEnvironment(environment: EnvironmentKey): RuntimeResult<Unit> =
        call(RuntimeRoute.RUN_ENVIRONMENT, listOf(environment.value), null, SuccessBodies.EMPTY)

    override suspend fun startEnvironment(environment: EnvironmentKey): RuntimeResult<Unit> =
        call(RuntimeRoute.START_ENVIRONMENT, listOf(environment.value), null, SuccessBodies.EMPTY)

    override suspend fun stopEnvironment(environment: EnvironmentKey): RuntimeResult<Unit> =
        call(RuntimeRoute.STOP_ENVIRONMENT, listOf(environment.value), null, SuccessBodies.EMPTY)

    override suspend fun health(): RuntimeResult<Unit> =
        call(RuntimeRoute.HEALTH, emptyList(), null, SuccessBodies.HEALTH)

    override suspend fun listEnvironments(): RuntimeResult<List<ActiveEnvironment>> =
        call(RuntimeRoute.LIST_ENVIRONMENTS, emptyList(), null, SuccessBodies.ENVIRONMENT_LIST)

    override suspend fun stateMachineSource(environment: EnvironmentKey, modelElement: String): RuntimeResult<String> =
        call(RuntimeRoute.STATE_MACHINE_SOURCE, listOf(environment.value, modelElement), null, SuccessBodies.STATE_MACHINE_SOURCE)

    override suspend fun registerObservatoryClient(
        client: ClientId,
        environment: EnvironmentKey,
        modelElement: String,
    ): RuntimeResult<AccessKey> =
        call(
            RuntimeRoute.REGISTER_OBSERVATORY_CLIENT,
            listOf(client.value, environment.value, modelElement),
            null,
            SuccessBodies.accessKey(client),
        )

    override suspend fun registeredFeatures(): RuntimeResult<Set<FeatureName>> =
        call(RuntimeRoute.GET_REGISTERED_FEATURES, emptyList(), null, SuccessBodies.FEATURE_NAMES)

    override suspend fun activateFeature(environment: EnvironmentKey, feature: FeatureName): RuntimeResult<Unit> =
        call(RuntimeRoute.ACTIVATE_FEATURE, listOf(environment.value, feature.value), null, SuccessBodies.EMPTY)

    override suspend fun activeFeatures(environment: EnvironmentKey): RuntimeResult<Set<FeatureName>> =
        call(RuntimeRoute.GET_ACTIVE_FEATURES, listOf(environment.value), null, SuccessBodies.FEATURE_NAMES)

    override suspend fun deactivateFeature(environment: EnvironmentKey, feature: FeatureName): RuntimeResult<Unit> =
        call(RuntimeRoute.DEACTIVATE_FEATURE, listOf(environment.value, feature.value), null, SuccessBodies.EMPTY)

    override suspend fun sendMessageToFeature(environment: EnvironmentKey, feature: FeatureName, message: String): RuntimeResult<String> =
        call(RuntimeRoute.SEND_MESSAGE_TO_FEATURE, listOf(environment.value, feature.value), message, SuccessBodies.FEATURE_ANSWER)

    override suspend fun modelSnapshot(environment: EnvironmentKey): RuntimeResult<RuntimeModelSnapshot> =
        call(RuntimeRoute.MODEL_SNAPSHOT, listOf(environment.value), null, SuccessBodies.snapshot(environment))

    override suspend fun subscribeModel(environment: EnvironmentKey, client: ClientId): RuntimeResult<Unit> =
        call(RuntimeRoute.SUBSCRIBE_MODEL, listOf(environment.value, client.value), null, SuccessBodies.EMPTY)

    override suspend fun unsubscribeModel(environment: EnvironmentKey, client: ClientId): RuntimeResult<Unit> =
        call(RuntimeRoute.UNSUBSCRIBE_MODEL, listOf(environment.value, client.value), null, SuccessBodies.EMPTY)

    override suspend fun probeSnapshotRoute(): RuntimeResult<Unit> {
        val route = RuntimeRoute.MODEL_SNAPSHOT
        val expected = RuntimeText.MISSING_PARAMETER.render(mapOf(PARAMETER_NAME to route.parameters.first()))
        return when (val outcome = exchange(route, TextRequest(route.verb, route.path, emptyList(), null))) {
            is Exchange.Failed -> RuntimeResult.Failed(outcome.failure)
            is Exchange.Answered -> if (outcome.answer.status == BAD_REQUEST && outcome.answer.body == expected) {
                RuntimeResult.Ok(Unit)
            } else {
                RuntimeResult.Failed(ResponseInterpreter.failure(route, outcome.answer))
            }
        }
    }

    /**
     * Calls [route] with the parameter [values] in catalogue order and the request [body], and reads a success with
     * [success]. A wrong number of values or a blank value is a programming error. It throws
     * [IllegalArgumentException] before anything is sent.
     */
    private suspend fun <T> call(route: RuntimeRoute, values: List<String>, body: String?, success: SuccessBody<T>): RuntimeResult<T> {
        require(values.size == route.parameters.size) { "$route takes ${route.parameters}, not $values" }
        route.parameters.zip(values).forEach { (name, value) ->
            require(value.isNotBlank()) { "the parameter '$name' of $route must not be blank" }
        }
        return when (val outcome = exchange(route, TextRequest(route.verb, route.path, route.parameters.zip(values), body))) {
            is Exchange.Failed -> RuntimeResult.Failed(outcome.failure)
            is Exchange.Answered -> ResponseInterpreter.interpret(route, outcome.answer, success)
        }
    }

    /** Sends [request] of [route] within its timeout and maps a transport failure to the route's failure. */
    private suspend fun exchange(route: RuntimeRoute, request: TextRequest): Exchange {
        val limit = timeouts.of(route.timeout)
        val start = timeSource.markNow()
        val outcome = withTimeoutOrNull(limit) { http.exchange(request) }
        logger.debug("{} {} took {}", route.verb, route.path, start.elapsedNow())
        return when (outcome) {
            null -> Exchange.Failed(RuntimeFailure.NoAnswer(route, "no answer within $limit", timedOut = true))
            is HttpOutcome.Answered -> Exchange.Answered(outcome)
            is HttpOutcome.Failed -> Exchange.Failed(failureOf(route, outcome.failure))
        }
    }

    /** The failure of [route] that the transport [failure] stands for. */
    private fun failureOf(route: RuntimeRoute, failure: TransportFailure): RuntimeFailure =
        when (failure) {
            is TransportFailure.Unreachable -> RuntimeFailure.Unreachable(route, failure.cause)
            is TransportFailure.Interrupted -> RuntimeFailure.NoAnswer(route, failure.cause, timedOut = false)
            is TransportFailure.TimedOut -> when (failure.phase) {
                TransportPhase.CONNECTING -> RuntimeFailure.Unreachable(route, failure.cause)
                TransportPhase.AWAITING_RESPONSE -> RuntimeFailure.NoAnswer(route, failure.cause, timedOut = true)
            }
        }

    /** How an exchange ended: an answer to interpret, or a failure of the route. */
    private sealed interface Exchange {
        /** The runtime answered. */
        data class Answered(val answer: HttpOutcome.Answered) : Exchange

        /** No answer came. */
        data class Failed(val failure: RuntimeFailure) : Exchange
    }

    private companion object {
        /** The logger of the call durations, at debug level. */
        private val logger = LoggerFactory.getLogger(HttpRuntimeApi::class.java)

        /** The placeholder of [RuntimeText.MISSING_PARAMETER] that names the parameter. */
        const val PARAMETER_NAME = "name"

        /** The status of a rejected argument. */
        const val BAD_REQUEST = 400
    }
}
