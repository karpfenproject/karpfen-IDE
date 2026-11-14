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
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot

/**
 * The raw gateway to the runtime. It has one function per [RuntimeRoute] with typed parameters and applies no
 * lifecycle guard. Only the client's own environment handles and the client facade call it, and the handles do the
 * guarding.
 *
 * Every function may be called from any coroutine, also concurrently. Each waits at most the timeout of its route's
 * timeout class.
 *
 * No function throws for a runtime or transport failure. Failures are [RuntimeResult.Failed]. A
 * `CancellationException` of the caller is always rethrown. Parameter values must not be blank, because the runtime
 * would read an empty value as present and fail later. A blank value is a programming error and throws
 * [IllegalArgumentException] before anything is sent.
 */
internal interface RuntimeApi {
    /** `POST /createEnvironment`: a new environment key. */
    suspend fun createEnvironment(): RuntimeResult<EnvironmentKey>

    /** `PUT /setMetamodel`: uploads the domain metamodel [text] of [environment]. */
    suspend fun setMetamodel(environment: EnvironmentKey, text: String): RuntimeResult<Unit>

    /** `PUT /setModel`: uploads the model [text] of [environment]. */
    suspend fun setModel(environment: EnvironmentKey, text: String): RuntimeResult<Unit>

    /** `PUT /setEventDefinitions`: uploads the event definitions [text] of [environment]. */
    suspend fun setEventDefinitions(environment: EnvironmentKey, text: String): RuntimeResult<Unit>

    /**
     * `PUT /setStateMachine`: attaches the state machine [text] to the object [attachedTo] of [environment], or keeps
     * the attachable state machine [text] under its name when [attachedTo] is null.
     */
    suspend fun setStateMachine(environment: EnvironmentKey, attachedTo: String?, text: String): RuntimeResult<Unit>

    /** `POST /setTickDelay`: sets the tick delay of [environment] to [milliseconds]. */
    suspend fun setTickDelay(environment: EnvironmentKey, milliseconds: Int): RuntimeResult<Unit>

    /** `POST /setActiveState`: forces the machine attached to [modelElement] of [environment] into [state]. */
    suspend fun setActiveState(environment: EnvironmentKey, modelElement: String, state: String): RuntimeResult<Unit>

    /** `POST /setEventTtl`: sets the event time to live of [environment] to [milliseconds]. */
    suspend fun setEventTtl(environment: EnvironmentKey, milliseconds: Long): RuntimeResult<Unit>

    /** `POST /registerObjectObserver`: registers [client] as an observer of the object [objectId] of [environment]. */
    suspend fun registerObjectObserver(environment: EnvironmentKey, client: ClientId, objectId: String): RuntimeResult<Unit>

    /** `POST /registerDomainListener`: registers [client] as a listener of the event [domain] of [environment]. */
    suspend fun registerDomainListener(environment: EnvironmentKey, client: ClientId, domain: String): RuntimeResult<Unit>

    /**
     * `POST /registerClientForWebSocket`: an access key of [client] for [environment], without an observatory
     * registration.
     */
    suspend fun registerClientForWebSocket(client: ClientId, environment: EnvironmentKey): RuntimeResult<AccessKey>

    /** `POST /runEnvironment`: activates [environment]. */
    suspend fun runEnvironment(environment: EnvironmentKey): RuntimeResult<Unit>

    /** `POST /startEnvironment`: starts [environment]. */
    suspend fun startEnvironment(environment: EnvironmentKey): RuntimeResult<Unit>

    /** `POST /stopEnvironment`: stops [environment]. */
    suspend fun stopEnvironment(environment: EnvironmentKey): RuntimeResult<Unit>

    /** `GET /health`: whether the runtime is up. */
    suspend fun health(): RuntimeResult<Unit>

    /** `GET /observatory/environments`: the activated, running, halted and stopped environments. */
    suspend fun listEnvironments(): RuntimeResult<List<ActiveEnvironment>>

    /**
     * `GET /observatory/statemachine`: the state machine text that the instance on [modelElement] of [environment]
     * runs, the attachable state machine it was built from or the text attached to the object.
     */
    suspend fun stateMachineSource(environment: EnvironmentKey, modelElement: String): RuntimeResult<String>

    /** `GET /observatory/stateMachines`: the attached state machines, the definitions and the instances of [environment]. */
    suspend fun stateMachines(environment: EnvironmentKey): RuntimeResult<StateMachineHoldings>

    /**
     * `POST /observatory/registerClient`: an access key of [client] for [environment]. The call also registers the
     * client for the stack changes of the machine on [modelElement]. The value `*` stands for every machine.
     */
    suspend fun registerObservatoryClient(client: ClientId, environment: EnvironmentKey, modelElement: String): RuntimeResult<AccessKey>

    /** `GET /getRegisteredFeatures`: the names of every feature the runtime knows. */
    suspend fun registeredFeatures(): RuntimeResult<Set<FeatureName>>

    /** `POST /activateFeature`: switches [feature] on in [environment], together with the features it needs. */
    suspend fun activateFeature(environment: EnvironmentKey, feature: FeatureName): RuntimeResult<Unit>

    /** `GET /getActiveFeatures`: the names of the features that are on in [environment]. */
    suspend fun activeFeatures(environment: EnvironmentKey): RuntimeResult<Set<FeatureName>>

    /** `POST /deactivateFeature`: switches [feature] off in [environment], together with the features that need it. */
    suspend fun deactivateFeature(environment: EnvironmentKey, feature: FeatureName): RuntimeResult<Unit>

    /** `POST /sendMessageToFeature`: sends [message] to [feature] in [environment] and returns the feature's answer. */
    suspend fun sendMessageToFeature(environment: EnvironmentKey, feature: FeatureName, message: String): RuntimeResult<String>

    /** `GET /observatory/model`: the model snapshot of [environment]. */
    suspend fun modelSnapshot(environment: EnvironmentKey): RuntimeResult<RuntimeModelSnapshot>

    /** `POST /observatory/subscribeModel`: subscribes [client] to every object and stack change of [environment]. */
    suspend fun subscribeModel(environment: EnvironmentKey, client: ClientId): RuntimeResult<Unit>

    /** `POST /observatory/unsubscribeModel`: ends the model subscription of [client] to [environment]. */
    suspend fun unsubscribeModel(environment: EnvironmentKey, client: ClientId): RuntimeResult<Unit>

    /**
     * Checks that the runtime has the routes of IDE support. It calls `GET /observatory/model` without parameters. A
     * runtime with IDE support answers that with 400 and exactly `Missing required parameter: envKey`, and that answer
     * becomes [RuntimeResult.Ok]. Any other answer is a failure, among them the 404 of a runtime without the route.
     */
    suspend fun probeSnapshotRoute(): RuntimeResult<Unit>
}
