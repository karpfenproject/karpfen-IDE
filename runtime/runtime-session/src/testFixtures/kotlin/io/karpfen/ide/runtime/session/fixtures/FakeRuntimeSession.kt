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
package io.karpfen.ide.runtime.session.fixtures

import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.connection.SendOutcome
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.EventFrameEncoder
import io.karpfen.ide.runtime.session.ObservedDomainEvent
import io.karpfen.ide.runtime.session.PreparedDeployment
import io.karpfen.ide.runtime.session.RuntimeSession
import io.karpfen.ide.runtime.session.SessionActivity
import io.karpfen.ide.runtime.session.SessionCommandOutcome
import io.karpfen.ide.runtime.session.SessionState
import io.karpfen.ide.runtime.session.deploy.DeployProgress
import io.karpfen.ide.runtime.session.events.EventInjector
import io.karpfen.ide.runtime.session.events.InjectionAvailability
import io.karpfen.ide.runtime.session.observation.ModelUpdate
import io.karpfen.ide.runtime.session.observation.ObservationMode
import io.karpfen.ide.runtime.session.observation.ObservedModel
import io.karpfen.ide.runtime.session.observation.SyncStatus
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.states.ActiveStatesReducer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A session for the tests of views that show one. The test sets what the session shows through the writable flows and
 * programs what its commands answer. The session records every command and every event frame it was asked to send. It
 * deploys nothing and talks to no runtime.
 *
 * At first the session is deploying, without progress, with the plan's findings as its problems and an idle
 * connection. Its model is the seed with the predicted states, and events are not injectable yet. Its event injector
 * is the real one over the prepared catalog. The frames it sends are recorded and answered as written.
 *
 * Every function may be called from any thread.
 *
 * @property prepared what the session pretends to deploy.
 * @property clientId the client id it shows.
 */
public class FakeRuntimeSession(
    override val prepared: PreparedDeployment,
    override val clientId: ClientId = ClientId("karpfen-ide-000000000000"),
) : RuntimeSession {
    /** Guards the records. */
    private val lock = Any()

    /** The commands asked so far. */
    private val askedCommands = ArrayList<FakeSessionCommand>()

    /** The event frames sent so far. */
    private val sentFrames = ArrayList<EventFrame>()

    /** The writable state. */
    public val stateFlow: MutableStateFlow<SessionState> = MutableStateFlow(SessionState.Deploying)

    /** The writable progress. */
    public val progressFlow: MutableStateFlow<DeployProgress> = MutableStateFlow(DeployProgress(emptyList(), null))

    /** The writable problems. */
    public val problemsFlow: MutableStateFlow<List<DeployProblem>> = MutableStateFlow(prepared.plan.findings)

    /** The writable connection state. */
    public val connectionFlow: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Idle)

    /** The writable observed model. */
    public val modelFlow: MutableStateFlow<ObservedModel> = MutableStateFlow(
        ObservedModel(ObservationMode.OFF, SyncStatus.Predicted, null, prepared.seed, ActiveStatesReducer(prepared.layouts).initial()),
    )

    /** The writable injection availability. */
    public val availabilityFlow: MutableStateFlow<InjectionAvailability> = MutableStateFlow(InjectionAvailability.NotYet)

    /** Where the test emits model updates. They reach collectors of [modelUpdates]. */
    public val modelUpdateFlow: MutableSharedFlow<ModelUpdate> = MutableSharedFlow(extraBufferCapacity = BUFFER)

    /** Where the test emits domain events. They reach collectors of [domainEvents]. */
    public val domainEventFlow: MutableSharedFlow<ObservedDomainEvent> = MutableSharedFlow(extraBufferCapacity = BUFFER)

    /** Where the test emits journal entries. They are replayed like the entries of the real journal. */
    public val activityFlow: MutableSharedFlow<SessionActivity> =
        MutableSharedFlow(replay = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** What each command answers. It is [SessionCommandOutcome.Done] unless the test programs otherwise. */
    @Volatile
    public var answer: (FakeSessionCommand) -> SessionCommandOutcome = { SessionCommandOutcome.Done }

    /** The commands asked so far, in order. */
    public val commands: List<FakeSessionCommand>
        get() = synchronized(lock) { askedCommands.toList() }

    /** The event frames sent so far, in order. */
    public val frames: List<EventFrame>
        get() = synchronized(lock) { sentFrames.toList() }

    /** True once [close] or [shutdown] was called. */
    @Volatile
    public var closed: Boolean = false
        private set

    override val state: StateFlow<SessionState> = stateFlow.asStateFlow()

    override val progress: StateFlow<DeployProgress> = progressFlow.asStateFlow()

    override val problems: StateFlow<List<DeployProblem>> = problemsFlow.asStateFlow()

    override val connection: StateFlow<ConnectionState> = connectionFlow.asStateFlow()

    override val model: StateFlow<ObservedModel> = modelFlow.asStateFlow()

    override val modelUpdates: Flow<ModelUpdate> = modelUpdateFlow.asSharedFlow()

    override val domainEvents: Flow<ObservedDomainEvent> = domainEventFlow.asSharedFlow()

    override val activity: Flow<SessionActivity> = activityFlow.asSharedFlow()

    override val events: EventInjector = EventInjector(
        catalog = prepared.catalog,
        attachedKeys = prepared.plan.attachments.mapTo(LinkedHashSet()) { it.attachedTo },
        availability = availabilityFlow.asStateFlow(),
        sink = { frame ->
            synchronized(lock) { sentFrames += frame }
            SendOutcome.Written(EventFrameEncoder.encode(frame))
        },
    )

    override suspend fun start(): SessionCommandOutcome = asked(FakeSessionCommand.Start)

    override suspend fun stop(): SessionCommandOutcome = asked(FakeSessionCommand.Stop)

    override suspend fun forceState(attachedTo: String, state: String): SessionCommandOutcome =
        asked(FakeSessionCommand.ForceState(attachedTo, state))

    override suspend fun setObservation(on: Boolean): SessionCommandOutcome = asked(FakeSessionCommand.SetObservation(on))

    override suspend fun pull(): SessionCommandOutcome = asked(FakeSessionCommand.Pull)

    override suspend fun shutdown() {
        asked(FakeSessionCommand.Shutdown)
        close()
    }

    override fun close() {
        closed = true
        stateFlow.value = SessionState.Closed
    }

    /** Records [command] and answers what the test programmed for it. */
    private fun asked(command: FakeSessionCommand): SessionCommandOutcome {
        synchronized(lock) { askedCommands += command }
        return answer(command)
    }

    private companion object {
        /** How many updates, events and journal entries wait for a collector. */
        private const val BUFFER = 64
    }
}

/** A command a [FakeRuntimeSession] was asked. Immutable. */
public sealed interface FakeSessionCommand {
    /** [RuntimeSession.start]. */
    public data object Start : FakeSessionCommand

    /** [RuntimeSession.stop]. */
    public data object Stop : FakeSessionCommand

    /**
     * [RuntimeSession.forceState].
     *
     * @property attachedTo the machine's key.
     * @property state the state.
     */
    public data class ForceState(public val attachedTo: String, public val state: String) : FakeSessionCommand

    /**
     * [RuntimeSession.setObservation].
     *
     * @property on the switch's position.
     */
    public data class SetObservation(public val on: Boolean) : FakeSessionCommand

    /** [RuntimeSession.pull]. */
    public data object Pull : FakeSessionCommand

    /** [RuntimeSession.shutdown]. */
    public data object Shutdown : FakeSessionCommand
}
