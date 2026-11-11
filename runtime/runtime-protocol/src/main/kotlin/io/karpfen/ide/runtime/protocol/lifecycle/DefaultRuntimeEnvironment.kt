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
package io.karpfen.ide.runtime.protocol.lifecycle

import io.karpfen.ide.runtime.protocol.api.RuntimeApi
import io.karpfen.ide.runtime.protocol.api.RuntimeResult
import io.karpfen.ide.runtime.protocol.catalogue.AccessKey
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * The environment handle over the gateway. Every call runs on [dispatcher] under the handle's mutex. The handle asks
 * [LifecycleRules] for the verdict before anything is sent, and a refusal sends nothing. After the outcome it asks the
 * rules for the next phase. When the rules ask for a probe, the handle reads a snapshot through [api] directly. It does
 * not go through [snapshot], which would take the mutex a second time. The gateway bounds the probe by the snapshot
 * timeout.
 *
 * Activation, start and stop run in a non-cancellable context together with their probes, bounded by the timeouts of
 * their routes. A cancelled caller waits for the outcome. The phase is recorded, and then the cancellation is
 * rethrown.
 *
 * @param key the environment's key.
 * @param api the gateway.
 * @param dispatcher where the calls run, so that the gateway's timeouts run there.
 */
internal class DefaultRuntimeEnvironment(
    override val key: EnvironmentKey,
    private val api: RuntimeApi,
    private val dispatcher: CoroutineDispatcher,
) : RuntimeEnvironment {

    /** The phase. It is written only under [mutex]. */
    private val mutablePhase = MutableStateFlow<EnvironmentPhase>(EnvironmentPhase.Configuring(Uploads.NONE))

    /** Serializes the calls, so they never overlap on the wire and the phase changes in call order. */
    private val mutex = Mutex()

    override val phase: StateFlow<EnvironmentPhase> = mutablePhase.asStateFlow()

    override fun allows(operation: LifecycleOperation): LifecycleRefusal? {
        val current = mutablePhase.value
        return when (val verdict = LifecycleRules.verdict(current, operation)) {
            is Verdict.Refuse -> LifecycleRefusal(operation, current, verdict.reason)
            Verdict.Send, Verdict.AnswerLocally -> null
        }
    }

    override suspend fun setMetamodel(text: String): CallOutcome<Unit> =
        perform(LifecycleOperation.SET_METAMODEL) { api.setMetamodel(key, text) }

    override suspend fun setEventDefinitions(text: String): CallOutcome<Unit> =
        perform(LifecycleOperation.SET_EVENT_DEFINITIONS) { api.setEventDefinitions(key, text) }

    override suspend fun setModel(text: String): CallOutcome<Unit> =
        perform(LifecycleOperation.SET_MODEL) { api.setModel(key, text) }

    override suspend fun setStateMachine(attachedTo: String, text: String): CallOutcome<Unit> =
        perform(LifecycleOperation.SET_STATE_MACHINE, attachedTo) { api.setStateMachine(key, attachedTo, text) }

    override suspend fun setTickDelay(milliseconds: Int): CallOutcome<Unit> =
        perform(LifecycleOperation.SET_TICK_DELAY) { api.setTickDelay(key, milliseconds) }

    override suspend fun setEventTtl(milliseconds: Long): CallOutcome<Unit> =
        perform(LifecycleOperation.SET_EVENT_TTL) { api.setEventTtl(key, milliseconds) }

    override suspend fun registerObjectObserver(client: ClientId, objectId: String): CallOutcome<Unit> =
        perform(LifecycleOperation.REGISTER_OBJECT_OBSERVER) { api.registerObjectObserver(key, client, objectId) }

    override suspend fun registerDomainListener(client: ClientId, domain: String): CallOutcome<Unit> =
        perform(LifecycleOperation.REGISTER_DOMAIN_LISTENER) { api.registerDomainListener(key, client, domain) }

    override suspend fun issueAccessKey(client: ClientId, observatory: Observatory): CallOutcome<AccessKey> =
        perform(LifecycleOperation.ISSUE_ACCESS_KEY) {
            when (observatory) {
                Observatory.NoMachine -> api.registerClientForWebSocket(client, key)
                Observatory.EveryMachine -> api.registerObservatoryClient(client, key, WireNames.EVERY_MACHINE)
                is Observatory.Machine -> api.registerObservatoryClient(client, key, observatory.modelElement)
            }
        }

    override suspend fun activate(): CallOutcome<Unit> =
        perform(LifecycleOperation.ACTIVATE, completesWhenCancelled = true) { api.runEnvironment(key) }

    override suspend fun start(): CallOutcome<Unit> =
        perform(LifecycleOperation.START, completesWhenCancelled = true) { api.startEnvironment(key) }

    override suspend fun stop(): CallOutcome<Unit> =
        perform(LifecycleOperation.STOP, completesWhenCancelled = true, localAnswer = Unit) { api.stopEnvironment(key) }

    override suspend fun forceState(modelElement: String, state: String): CallOutcome<Unit> =
        perform(LifecycleOperation.FORCE_STATE) { api.setActiveState(key, modelElement, state) }

    override suspend fun stateMachineSource(modelElement: String): CallOutcome<String> =
        perform(LifecycleOperation.READ_STATE_MACHINE_SOURCE) { api.stateMachineSource(key, modelElement) }

    override suspend fun snapshot(): CallOutcome<RuntimeModelSnapshot> =
        perform(LifecycleOperation.READ_SNAPSHOT, settle = LifecycleRules::afterSnapshot) { api.modelSnapshot(key) }

    override suspend fun subscribeModel(client: ClientId): CallOutcome<Unit> =
        perform(LifecycleOperation.SUBSCRIBE_MODEL) { api.subscribeModel(key, client) }

    override suspend fun unsubscribeModel(client: ClientId): CallOutcome<Unit> =
        perform(LifecycleOperation.UNSUBSCRIBE_MODEL) { api.unsubscribeModel(key, client) }

    /**
     * Performs [operation] with [call] under the mutex. A refusing verdict refuses the operation. A verdict to answer
     * locally returns [localAnswer]. Otherwise the handle calls the runtime and records the phase that follows the
     * outcome, and [settle] decides the phase after a success. With [completesWhenCancelled], the call and its probe
     * run to their end even when the caller is cancelled. The cancellation is rethrown after the phase was recorded.
     */
    private suspend fun <T> perform(
        operation: LifecycleOperation,
        attachedTo: String? = null,
        completesWhenCancelled: Boolean = false,
        localAnswer: T? = null,
        settle: (EnvironmentPhase, T) -> EnvironmentPhase = { before, _ -> LifecycleRules.afterSuccess(before, operation, attachedTo) },
        call: suspend () -> RuntimeResult<T>,
    ): CallOutcome<T> = withContext(dispatcher) {
        mutex.withLock {
            val before = mutablePhase.value
            when (val verdict = LifecycleRules.verdict(before, operation)) {
                is Verdict.Refuse -> CallOutcome.Refused(LifecycleRefusal(operation, before, verdict.reason))
                Verdict.AnswerLocally -> CallOutcome.Done(checkNotNull(localAnswer) { "$operation has no local answer" })
                Verdict.Send -> if (completesWhenCancelled) {
                    val outcome = withContext(NonCancellable) { callAndRecord(operation, settle, call) }
                    currentCoroutineContext().ensureActive()
                    outcome
                } else {
                    callAndRecord(operation, settle, call)
                }
            }
        }
    }

    /**
     * Calls [call] for [operation] and records the phase that follows its outcome. After a success [settle] decides
     * the phase. After a failure the rules decide it, and the handle probes first when the rules ask for it.
     */
    private suspend fun <T> callAndRecord(
        operation: LifecycleOperation,
        settle: (EnvironmentPhase, T) -> EnvironmentPhase,
        call: suspend () -> RuntimeResult<T>,
    ): CallOutcome<T> {
        val before = mutablePhase.value
        return when (val result = call()) {
            is RuntimeResult.Ok -> {
                record(operation, settle(before, result.value))
                CallOutcome.Done(result.value)
            }
            is RuntimeResult.Failed -> {
                val after = when (val resolution = LifecycleRules.afterFailure(before, operation, result.failure)) {
                    is OutcomeResolution.Settled -> resolution.phase
                    OutcomeResolution.Probe -> LifecycleRules.afterProbe(before, operation, probe())
                }
                record(operation, after)
                CallOutcome.Failed(result.failure)
            }
        }
    }

    /** Reads a snapshot through the gateway to settle the phase after a call whose effect is unknown. */
    private suspend fun probe(): ProbeResult =
        when (val result = api.modelSnapshot(key)) {
            is RuntimeResult.Ok -> ProbeResult.Reported(result.value)
            is RuntimeResult.Failed -> ProbeResult.Failed(result.failure)
        }

    /** Records [after] as the phase following [operation]. */
    private fun record(operation: LifecycleOperation, after: EnvironmentPhase) {
        val before = mutablePhase.value
        if (before != after) logger.debug("Environment {}: {} moved the phase from {} to {}", key, operation, before, after)
        mutablePhase.value = after
    }

    private companion object {
        /** The logger of phase changes, at debug level. */
        private val logger = LoggerFactory.getLogger(DefaultRuntimeEnvironment::class.java)
    }
}
