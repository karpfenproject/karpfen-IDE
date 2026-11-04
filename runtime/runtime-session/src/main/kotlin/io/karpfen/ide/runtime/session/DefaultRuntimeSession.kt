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
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.session.deploy.CleanupBounds
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import io.karpfen.ide.runtime.session.deploy.DeployProgress
import io.karpfen.ide.runtime.session.deploy.Deployer
import io.karpfen.ide.runtime.session.deploy.EnvironmentLedger
import io.karpfen.ide.runtime.session.deploy.FailureLocator
import io.karpfen.ide.runtime.session.events.EventInjector
import io.karpfen.ide.runtime.session.observation.ModelUpdate
import io.karpfen.ide.runtime.session.observation.ObservationSettings
import io.karpfen.ide.runtime.session.observation.ObservedModel
import io.karpfen.ide.runtime.session.problem.DeployProblem
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The session facade: composes the deployment, the conductor with its calls and its stopper, the router, the published
 * status and the event injector of one deployment, and delegates every function to the one that owns it. It runs in its
 * own supervisor child of the scope it was launched in, on the dispatcher of the options; the conductor's, the router's
 * and the deployment's coroutines and the connection live in it. When that child ends because the scope it was
 * launched in was cancelled, the session closes itself, so nobody waits for an answer that never comes and the
 * environment is recorded as it is left.
 *
 * @param prepared what the session deploys.
 * @param client the client of the runtime.
 * @param parent the scope the session was launched in.
 * @param deployOptions how the deployment ends.
 * @param options how the session runs.
 * @param ledger where environments the session leaves are recorded.
 * @param deployer runs the deployment.
 */
internal class DefaultRuntimeSession(
    override val prepared: PreparedDeployment,
    client: RuntimeClient,
    parent: CoroutineScope,
    deployOptions: DeployOptions,
    options: RuntimeSessionOptions,
    ledger: EnvironmentLedger,
    deployer: Deployer,
) : RuntimeSession {

    override val clientId: ClientId = ClientId.random(options.random)

    /** The session's supervisor job, a child of the parent's. */
    private val job = SupervisorJob(parent.coroutineContext[Job])

    /** The scope of the session's work. */
    private val scope = CoroutineScope(parent.coroutineContext + job + options.dispatcher + CoroutineName("runtime-session-$clientId"))

    /** True once the session was closed. */
    private val closed = AtomicBoolean(false)

    /** The session's clock. */
    private val clock = SessionClock(options.timeSource)

    /** The conductor's inbox, which the router reports into. */
    private val inbox = ConductorInbox()

    /** What the session publishes about itself. */
    private val status = SessionStatus(prepared.plan.findings)

    /** The router of the connection's pushes. */
    private val router = PushRouter(
        prepared = prepared,
        clientId = clientId,
        settings = ObservationSettings(
            subscribeAttempts = options.subscribeAttempts,
            subscribeRetryDelay = options.subscribeRetryDelay,
            snapshotAttempts = options.snapshotAttempts,
            snapshotRetryDelay = options.snapshotRetryDelay,
        ),
        clock = clock,
        conductor = inbox,
        scope = scope,
        journalCapacity = options.journalCapacity,
    )

    /** Records the environment once and settles a close that races the deployment or a stop. */
    private val departure = EnvironmentDeparture(ledger, client.endpoint)

    /** The bounds of stopping and disconnecting, those of a deployment's cleanup. */
    private val bounds = CleanupBounds.of(options.deployTiming)

    /** The conductor of the commands. */
    private val conductor = SessionConductor(
        startsEnvironment = deployOptions.start,
        router = router,
        inbox = inbox,
        status = status,
        departure = departure,
        calls = SessionCalls(router, FailureLocator(prepared.plan, client.endpoint), status, clock),
        stopper = EnvironmentStopper(router, inbox, departure, bounds, scope),
        clock = clock,
        scope = scope,
    )

    /** The deployment. */
    private val deployment = SessionDeployment(prepared, client, clientId, deployOptions, deployer, router, inbox, departure, bounds, scope)

    override val state: StateFlow<SessionState>
        get() = status.state

    override val progress: StateFlow<DeployProgress>
        get() = status.progress

    override val problems: StateFlow<List<DeployProblem>>
        get() = status.problems

    override val connection: StateFlow<ConnectionState>
        get() = router.connection

    override val model: StateFlow<ObservedModel>
        get() = router.model

    override val modelUpdates: Flow<ModelUpdate>
        get() = router.modelUpdates

    override val domainEvents: Flow<ObservedDomainEvent>
        get() = router.domainEvents

    override val activity: Flow<SessionActivity>
        get() = router.activity

    override val events: EventInjector = EventInjector(
        catalog = prepared.catalog,
        attachedKeys = prepared.plan.attachments.mapTo(LinkedHashSet()) { it.attachedTo },
        availability = status.availability,
        sink = ConductorEventSink(conductor),
    )

    /**
     * Starts the router, the conductor and the deployment, and closes the session once its job ended, which it does
     * after [close] and after a cancellation of the scope it was launched in.
     */
    fun begin() {
        job.invokeOnCompletion { close() }
        router.start()
        conductor.start()
        deployment.start()
    }

    override suspend fun start(): SessionCommandOutcome = conductor.execute(SessionCommand.Start)

    override suspend fun stop(): SessionCommandOutcome = conductor.execute(SessionCommand.Stop)

    override suspend fun forceState(attachedTo: String, state: String): SessionCommandOutcome =
        conductor.execute(SessionCommand.ForceState(attachedTo, state))

    override suspend fun setObservation(on: Boolean): SessionCommandOutcome = conductor.execute(SessionCommand.SetObservation(on))

    override suspend fun pull(): SessionCommandOutcome = conductor.execute(SessionCommand.Pull)

    override suspend fun shutdown() {
        conductor.shutdown()
        close()
        job.join()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        conductor.close()
        router.close()
        job.cancel()
    }
}
