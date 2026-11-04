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
package io.karpfen.ide.runtime.session

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.connection.SendOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleRefusal
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.protocol.lifecycle.Uploads
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.session.deploy.DeployObservation
import io.karpfen.ide.runtime.session.events.EventSink
import io.karpfen.ide.runtime.session.mirror.MirrorComparison
import io.karpfen.ide.runtime.session.mirror.MirrorReducer
import io.karpfen.ide.runtime.session.observation.ObservationPort
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes

/**
 * The observation's calls for one session: the model subscription and the snapshots of the environment, as the
 * session's client id. The environment is bound once the deployment created it; calls before that are refused.
 * Confined to the session's router, which binds it and whose observation calls it.
 *
 * @param clientId the session's client id.
 */
internal class BoundObservationPort(private val clientId: ClientId) : ObservationPort {
    /** The environment, once the deployment created it. */
    private var environment: RuntimeEnvironment? = null

    /** Binds the port to [environment]. */
    fun bind(environment: RuntimeEnvironment) {
        this.environment = environment
    }

    override suspend fun subscribe(): CallOutcome<Unit> =
        environment?.subscribeModel(clientId) ?: notCreated(LifecycleOperation.SUBSCRIBE_MODEL)

    override suspend fun unsubscribe(): CallOutcome<Unit> =
        environment?.unsubscribeModel(clientId) ?: notCreated(LifecycleOperation.UNSUBSCRIBE_MODEL)

    override suspend fun snapshot(): CallOutcome<RuntimeModelSnapshot> =
        environment?.snapshot() ?: notCreated(LifecycleOperation.READ_SNAPSHOT)

    /** The refusal of [operation] before the environment was created. */
    private fun notCreated(operation: LifecycleOperation): CallOutcome.Refused =
        CallOutcome.Refused(LifecycleRefusal(operation, EnvironmentPhase.Configuring(Uploads.NONE), "The environment is not created yet."))
}

/**
 * The deployment's way into the session's observation: switching on and pulling go through the session's router, so
 * the deployment's reads of the model are the observation's reads; the differences of the model the runtime read back
 * compare it with the seed, object by object, located at the objects' declarations in the uploaded model.
 *
 * @param router the session's router.
 * @param prepared what the session deploys.
 */
internal class SessionDeployObservation(private val router: PushRouter, private val prepared: PreparedDeployment) : DeployObservation {
    /** Reads the runtime's object trees as the seed was built. */
    private val reader = MirrorReducer(prepared.schema, prepared.plan.modelObjectIds)

    override suspend fun switchOn(): CallOutcome<RuntimeModelSnapshot> = router.switchOn()

    override suspend fun pull(): CallOutcome<RuntimeModelSnapshot> = router.pull()

    override fun differences(snapshot: RuntimeModelSnapshot): List<DeployProblem> {
        val read = reader.replace(snapshot.roots, snapshot.revision).snapshot
        val model = prepared.plan.model.file
        return MirrorComparison.differences(prepared.seed, read).map { difference ->
            DeployProblem(
                Severity.WARNING,
                DeployProblemCodes.MODEL_DIFFERS,
                "The runtime read the model differently from the IDE: ${difference.detail}",
                prepared.plan.locations.objectIn(model, difference.id),
            )
        }
    }
}

/**
 * The event sink of a session: every frame goes through the conductor, in order with the commands, so an injected
 * event never overtakes a stop.
 *
 * @param conductor the session's conductor.
 */
internal class ConductorEventSink(private val conductor: SessionConductor) : EventSink {
    override suspend fun send(frame: EventFrame): SendOutcome = conductor.inject(frame)
}
