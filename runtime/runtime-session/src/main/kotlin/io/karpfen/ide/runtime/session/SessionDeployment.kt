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

import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.connection.RuntimeConnection
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.session.deploy.CleanupBounds
import io.karpfen.ide.runtime.session.deploy.DeployListener
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import io.karpfen.ide.runtime.session.deploy.DeployProgress
import io.karpfen.ide.runtime.session.deploy.DeployRequest
import io.karpfen.ide.runtime.session.deploy.Deployer
import io.karpfen.ide.runtime.session.deploy.DeploymentResult
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * The deployment of one session, run in a coroutine of its own in the session's scope, so that the conductor answers
 * commands while it runs. Everything it learns goes to the conductor's inbox in order: the progress, the created
 * environment and the end; the connection goes to the router before it is opened, so the router reads its events from
 * the first opening on. The deployment reaches the observation through [SessionDeployObservation].
 *
 * Closing the session cancels the deployment, whose own cleanup then runs. A deployment that succeeded although the
 * session was closed meanwhile (the close came after the last suspension) is stopped, disconnected and recorded here,
 * as that cleanup would have done.
 *
 * @param prepared what the session deploys.
 * @param client the client of the runtime.
 * @param clientId the session's client id.
 * @param options how the deployment ends.
 * @param deployer runs the deployment.
 * @param router the session's router.
 * @param inbox the conductor's inbox.
 * @param departure records the environment once and tells whether the session was closed meanwhile.
 * @param bounds the bounds of stopping and disconnecting.
 * @param scope the session's scope, in which the deployment and its connection run.
 */
internal class SessionDeployment(
    private val prepared: PreparedDeployment,
    private val client: RuntimeClient,
    private val clientId: ClientId,
    private val options: DeployOptions,
    private val deployer: Deployer,
    private val router: PushRouter,
    private val inbox: ConductorInbox,
    private val departure: EnvironmentDeparture,
    private val bounds: CleanupBounds,
    private val scope: CoroutineScope,
) {
    /** Starts deploying in the session's scope. */
    fun start() {
        scope.launch(CoroutineName("session-deployment")) { deploy() }
    }

    /** Runs the deployment and hands its end to the inbox. */
    private suspend fun deploy() {
        val request = DeployRequest(prepared.plan, client, clientId, options, scope, SessionDeployObservation(router, prepared))
        val result = try {
            deployer.deploy(request, Listener())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (broken: Exception) {
            logger.error("The deployment of {} broke", prepared.plan.name, broken)
            inbox.offer(ConductorMessage.DeployBroke(broken))
            return
        }
        if (departure.deployEnded(result) && result is DeploymentResult.Deployed) {
            withContext(NonCancellable) { abandon(result) }
        }
        inbox.offer(ConductorMessage.DeployEnded(result))
    }

    /**
     * Takes care of an environment the deployment finished after the session was closed: stops it, as the cleanup of
     * a cancelled deployment would, disconnects and records it.
     */
    private suspend fun abandon(deployed: DeploymentResult.Deployed) {
        val environment = deployed.environment
        withTimeoutOrNull(bounds.stop) { environment.stop() }
        withTimeoutOrNull(bounds.disconnect) { deployed.connection.disconnect() }
        deployed.connection.close()
        departure.leave(environment.key, environment.phase.value, "Stopped because the session was closed while it deployed.")
    }

    /**
     * The deployment's listener: hands the progress and the created environment to the inbox and the connection, before
     * it is opened, to the router. Confined to the deploying coroutine.
     */
    private inner class Listener : DeployListener {
        /** The created environment, which the connection belongs to. */
        private var environment: RuntimeEnvironment? = null

        override fun progress(progress: DeployProgress) {
            inbox.offer(ConductorMessage.Progressed(progress))
        }

        override fun environmentCreated(environment: RuntimeEnvironment) {
            this.environment = environment
            inbox.offer(ConductorMessage.EnvironmentCreated(environment))
        }

        override fun connected(connection: RuntimeConnection) {
            router.attach(checkNotNull(environment) { "the connection belongs to the created environment" }, connection)
        }
    }

    private companion object {
        /** The deployment's logger. */
        private val logger = LoggerFactory.getLogger(SessionDeployment::class.java)
    }
}
