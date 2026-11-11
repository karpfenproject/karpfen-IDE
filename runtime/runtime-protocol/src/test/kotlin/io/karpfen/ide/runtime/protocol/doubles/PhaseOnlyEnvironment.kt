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
package io.karpfen.ide.runtime.protocol.doubles

import io.karpfen.ide.runtime.protocol.catalogue.AccessKey
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleRefusal
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleRules
import io.karpfen.ide.runtime.protocol.lifecycle.Observatory
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.protocol.lifecycle.Verdict
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * An environment handle whose phase the test sets. [allows] returns the lifecycle's verdicts for that phase, and every
 * call is refused without a runtime behind it. The connection only reads the phase and the verdicts of a handle.
 *
 * @property key the environment's key.
 * @param phase the phase it starts in.
 */
class PhaseOnlyEnvironment(
    override val key: EnvironmentKey = EnvironmentKey("env-1761555600000"),
    phase: EnvironmentPhase = EnvironmentPhase.Running,
) : RuntimeEnvironment {

    /** The phase the test set. */
    private val mutablePhase = MutableStateFlow(phase)

    override val phase: StateFlow<EnvironmentPhase> get() = mutablePhase

    /** Moves the handle to [next]. */
    fun moveTo(next: EnvironmentPhase) {
        mutablePhase.value = next
    }

    override fun allows(operation: LifecycleOperation): LifecycleRefusal? =
        when (val verdict = LifecycleRules.verdict(mutablePhase.value, operation)) {
            is Verdict.Refuse -> LifecycleRefusal(operation, mutablePhase.value, verdict.reason)
            else -> null
        }

    override suspend fun setMetamodel(text: String): CallOutcome<Unit> = refuse(LifecycleOperation.SET_METAMODEL)

    override suspend fun setEventDefinitions(text: String): CallOutcome<Unit> = refuse(LifecycleOperation.SET_EVENT_DEFINITIONS)

    override suspend fun setModel(text: String): CallOutcome<Unit> = refuse(LifecycleOperation.SET_MODEL)

    override suspend fun setStateMachine(attachedTo: String, text: String): CallOutcome<Unit> = refuse(LifecycleOperation.SET_STATE_MACHINE)

    override suspend fun setTickDelay(milliseconds: Int): CallOutcome<Unit> = refuse(LifecycleOperation.SET_TICK_DELAY)

    override suspend fun setEventTtl(milliseconds: Long): CallOutcome<Unit> = refuse(LifecycleOperation.SET_EVENT_TTL)

    override suspend fun registerObjectObserver(client: ClientId, objectId: String): CallOutcome<Unit> =
        refuse(LifecycleOperation.REGISTER_OBJECT_OBSERVER)

    override suspend fun registerDomainListener(client: ClientId, domain: String): CallOutcome<Unit> =
        refuse(LifecycleOperation.REGISTER_DOMAIN_LISTENER)

    override suspend fun issueAccessKey(client: ClientId, observatory: Observatory): CallOutcome<AccessKey> =
        refuse(LifecycleOperation.ISSUE_ACCESS_KEY)

    override suspend fun activate(): CallOutcome<Unit> = refuse(LifecycleOperation.ACTIVATE)

    override suspend fun start(): CallOutcome<Unit> = refuse(LifecycleOperation.START)

    override suspend fun stop(): CallOutcome<Unit> = refuse(LifecycleOperation.STOP)

    override suspend fun forceState(modelElement: String, state: String): CallOutcome<Unit> = refuse(LifecycleOperation.FORCE_STATE)

    override suspend fun stateMachineSource(modelElement: String): CallOutcome<String> =
        refuse(LifecycleOperation.READ_STATE_MACHINE_SOURCE)

    override suspend fun snapshot(): CallOutcome<RuntimeModelSnapshot> = refuse(LifecycleOperation.READ_SNAPSHOT)

    override suspend fun subscribeModel(client: ClientId): CallOutcome<Unit> = refuse(LifecycleOperation.SUBSCRIBE_MODEL)

    override suspend fun unsubscribeModel(client: ClientId): CallOutcome<Unit> = refuse(LifecycleOperation.UNSUBSCRIBE_MODEL)

    /** The refusal of [operation], since this double has no runtime behind it. */
    private fun refuse(operation: LifecycleOperation): CallOutcome<Nothing> =
        CallOutcome.Refused(LifecycleRefusal(operation, mutablePhase.value, "the double has no runtime"))
}
