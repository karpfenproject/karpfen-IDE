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
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.connection.ConnectionEvent
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.connection.RuntimeConnection
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleRefusal
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.protocol.lifecycle.Uploads
import io.karpfen.ide.runtime.protocol.message.EngineStatus
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.session.mirror.MirrorReducer
import io.karpfen.ide.runtime.session.observation.ModelObserver
import io.karpfen.ide.runtime.session.observation.ModelUpdate
import io.karpfen.ide.runtime.session.observation.ObservationNotice
import io.karpfen.ide.runtime.session.observation.ObservationReducer
import io.karpfen.ide.runtime.session.observation.ObservationSettings
import io.karpfen.ide.runtime.session.observation.ObservedModel
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import io.karpfen.ide.runtime.session.states.ActiveStatesReducer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/** What the router of a session takes, in the order it arrives. */
internal sealed interface RouterMessage {
    /**
     * The deployment connected. From now on the router reads the connection's events and observes the environment.
     *
     * @property environment the environment's handle.
     * @property connection the connection, not opened yet.
     */
    data class Attach(val environment: RuntimeEnvironment, val connection: RuntimeConnection) : RouterMessage

    /**
     * The connection reported [event].
     *
     * @property event the event.
     */
    data class Event(val event: ConnectionEvent) : RouterMessage

    /**
     * The connection's state changed.
     *
     * @property state the new state.
     */
    data class StateSeen(val state: ConnectionState) : RouterMessage

    /**
     * An entry for the journal from the conductor.
     *
     * @property activity the entry.
     */
    data class Record(val activity: SessionActivity) : RouterMessage

    /**
     * A call of the observation, answered through [reply].
     *
     * @property call what to do.
     * @property reply where the outcome goes.
     */
    data class Observe<T>(val call: suspend (ModelObserver) -> T, val reply: CompletableDeferred<T>) : RouterMessage
}

/**
 * The router of a session. It mediates between the connection and the session's views. It is the only collector of
 * the connection's events. It forwards them into its own unbounded inbox and takes them out in arrival order. The same
 * inbox also carries the observation calls of the conductor and the deployment, and the journal entries of the
 * conductor. The router owns the [ModelObserver]. It is the single writer of the observed model, the model updates, the
 * domain events, the engine statuses, the connection's state as the session shows it, and the journal.
 *
 * - Object pushes, stack pushes, openings, interruptions and the end of the connection go to the observation. A push
 *   that the observation applied is journaled.
 * - Domain events go to [domainEvents] and to the journal. Engine statuses go to [engineStatuses], whose collector is
 *   the session's debugger, which journals what it makes of them. Protocol violations, anomalies and unknown message
 *   types go to the journal only.
 * - The end of the connection, its state changes and observation problems are reported to the conductor without
 *   suspending. The router never waits for the conductor, so the two never wait for each other.
 * - The model updates, the domain events and the engine statuses are published without suspending. A collector that falls behind misses the
 *   oldest of them. So the router never waits for a collector either. This matters because a collector may call the
 *   session's commands, and those wait for the router.
 *
 * An observation call runs to its end before the router takes the next message. That includes its snapshot reads and
 * their retries. Pushes that arrive meanwhile wait in the inbox. This keeps every push after a snapshot in arrival
 * order.
 *
 * @param prepared what the session deploys. The router takes the schema, the object keys, the layouts and the seed of
 *   the observation from it.
 * @param clientId the session's client id, as which the observation subscribes.
 * @param settings the observation's retries and how far a collector of the model updates may fall behind.
 * @param clock the session's clock.
 * @param conductor where the router reports to.
 * @param scope the session's scope, in which the router and its forwarders run.
 * @param journalCapacity how many entries the journal keeps.
 * @param collectorBuffer how many domain events, and how many engine statuses, a collector may fall behind before it
 *   misses the oldest of them.
 */
internal class PushRouter(
    prepared: PreparedDeployment,
    clientId: ClientId,
    settings: ObservationSettings,
    private val clock: SessionClock,
    private val conductor: ConductorInbox,
    private val scope: CoroutineScope,
    journalCapacity: Int,
    collectorBuffer: Int,
) {
    /** The router's messages, unbounded. */
    private val inbox = Channel<RouterMessage>(Channel.UNLIMITED)

    /** The observation's calls, bound to the environment once it is attached. */
    private val port = BoundObservationPort(clientId)

    /** The live switch and the observed model. */
    private val observer = ModelObserver(
        reducer = ObservationReducer(MirrorReducer(prepared.schema, prepared.plan.modelObjectIds), ActiveStatesReducer(prepared.layouts)),
        port = port,
        seed = prepared.seed,
        settings = settings,
        listener = ::notice,
    )

    /** The journal. */
    private val journal = SessionJournal(journalCapacity)

    /** The writable domain events, hot and without replay. A collector that falls behind misses the oldest. */
    private val domainEventStream = MutableSharedFlow<ObservedDomainEvent>(
        extraBufferCapacity = collectorBuffer,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The writable engine statuses, hot and without replay. A collector that falls behind misses the oldest, so it
     * still gets the newest status.
     */
    private val engineStatusStream = MutableSharedFlow<EngineStatus>(
        extraBufferCapacity = collectorBuffer,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Guards [sealed] and the writes of [connectionState]. */
    private val lock = Any()

    /** True once the session was closed. The connection's state is final from then on. */
    private var sealed = false

    /** The writable connection state. */
    private val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)

    /** The attached connection, read when the session closes. */
    private val attached = AtomicReference<RuntimeConnection?>(null)

    /** The observed model. */
    val model: StateFlow<ObservedModel>
        get() = observer.model

    /** Every change of the observed model, hot and without replay. It never makes the router wait. */
    val modelUpdates: Flow<ModelUpdate>
        get() = observer.updates

    /** Every domain event, hot and without replay. It never makes the router wait. */
    val domainEvents: Flow<ObservedDomainEvent> = domainEventStream.asSharedFlow()

    /**
     * Every engine status the runtime pushed, in arrival order, hot and without replay. It never makes the router wait.
     * The runtime pushes one when the engine thread starts to wait and one when it goes on, while its feature
     * `TickByTick` is on.
     */
    val engineStatuses: Flow<EngineStatus> = engineStatusStream.asSharedFlow()

    /** The connection's state as the session shows it. It is idle until the deployment connects. */
    val connection: StateFlow<ConnectionState> = connectionState.asStateFlow()

    /** The journal of the session. */
    val activity: Flow<SessionActivity>
        get() = journal.activity

    /** Starts taking messages in the session's scope. */
    fun start() {
        scope.launch(CoroutineName("session-router")) {
            for (message in inbox) take(message)
        }
    }

    /** Attaches the [connection] of [environment] before it is opened, so that its first opening is read. */
    fun attach(environment: RuntimeEnvironment, connection: RuntimeConnection) {
        inbox.trySend(RouterMessage.Attach(environment, connection))
    }

    /** Records [activity] in the journal, in order with what the router records itself. */
    fun record(activity: SessionActivity) {
        inbox.trySend(RouterMessage.Record(activity))
    }

    /** Switches observation on and answers the snapshot it starts from. */
    suspend fun switchOn(): CallOutcome<RuntimeModelSnapshot> =
        observe(LifecycleOperation.SUBSCRIBE_MODEL) { it.switchOn() }

    /** Switches observation off. */
    suspend fun switchOff(): CallOutcome<Unit> = observe(LifecycleOperation.UNSUBSCRIBE_MODEL) { it.switchOff() }

    /** Reads a snapshot once and applies it. */
    suspend fun pull(): CallOutcome<RuntimeModelSnapshot> = observe(LifecycleOperation.READ_SNAPSHOT) { it.pull() }

    /** Switches observation off for good and reads the final model of the stopped environment. */
    suspend fun environmentStopped(): CallOutcome<RuntimeModelSnapshot> =
        observe(LifecycleOperation.READ_SNAPSHOT) { it.environmentStopped() }

    /**
     * Ends the router without suspending. No message is taken any more, and the connection's state as the session
     * shows it becomes the attached connection's final state.
     */
    fun close() {
        inbox.close()
        synchronized(lock) {
            sealed = true
            attached.get()?.let { connectionState.value = it.state.value }
        }
    }

    /**
     * Runs [call] on the observer in the router's order and returns its outcome. Once the router is closed, the call
     * is refused.
     */
    private suspend fun <T> observe(operation: LifecycleOperation, call: suspend (ModelObserver) -> CallOutcome<T>): CallOutcome<T> {
        val reply = CompletableDeferred<CallOutcome<T>>()
        if (inbox.trySend(RouterMessage.Observe(call, reply)).isFailure) {
            return CallOutcome.Refused(LifecycleRefusal(operation, EnvironmentPhase.Configuring(Uploads.NONE), "The session is closed."))
        }
        return reply.await()
    }

    /**
     * Takes one [message]. An exception that should never have been thrown is logged and fails the reply that the
     * message carries.
     */
    private suspend fun take(message: RouterMessage) {
        try {
            when (message) {
                is RouterMessage.Attach -> attachNow(message)
                is RouterMessage.Event -> handle(message.event)
                is RouterMessage.StateSeen -> stateSeen(message.state)
                is RouterMessage.Record -> journal.record(message.activity)
                is RouterMessage.Observe<*> -> answer(message)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (broken: Exception) {
            logger.error("The router of a session failed on {}", message, broken)
            (message as? RouterMessage.Observe<*>)?.reply?.completeExceptionally(broken)
        }
    }

    /** Runs the observation call of [message] and answers it. */
    private suspend fun <T> answer(message: RouterMessage.Observe<T>) {
        message.reply.complete(message.call(observer))
    }

    /** Binds the observation to the environment of [message] and forwards the connection's events and states. */
    private fun attachNow(message: RouterMessage.Attach) {
        port.bind(message.environment)
        attached.set(message.connection)
        scope.launch(CoroutineName("session-router-events")) {
            message.connection.events.collect { inbox.trySend(RouterMessage.Event(it)) }
        }
        scope.launch(CoroutineName("session-router-states")) {
            message.connection.state.collect { inbox.trySend(RouterMessage.StateSeen(it)) }
        }
    }

    /** Takes one [event] of the connection. */
    private suspend fun handle(event: ConnectionEvent) {
        when (event) {
            is ConnectionEvent.Received -> received(event)
            is ConnectionEvent.Violated -> journal(SessionActivity.Anomaly("The runtime sent a frame that breaks the protocol: ${event.violation.detail}", clock.elapsed()))
            is ConnectionEvent.Ended -> {
                observer.handle(event)
                conductor.offer(ConductorMessage.ConnectionEnded(event.end))
            }
            is ConnectionEvent.Opened, is ConnectionEvent.Interrupted -> observer.handle(event)
        }
    }

    /** Routes the push of [event]. */
    private suspend fun received(event: ConnectionEvent.Received) {
        when (val push = event.push) {
            is RuntimePush.DomainEvent -> {
                val observed = ObservedDomainEvent(push.domain, push.event, push.rawPayload, clock.elapsed())
                domainEventStream.tryEmit(observed)
                journal(SessionActivity.DomainEventReceived(observed, observed.elapsed))
            }
            is RuntimePush.EngineStatusChanged -> engineStatusStream.tryEmit(push.status)
            is RuntimePush.Unrecognized ->
                journal(SessionActivity.Note("The runtime pushed a message of the unknown type '${push.messageType}'; it is ignored.", clock.elapsed()))
            is RuntimePush.ObjectChanged -> applied(event, WireNames.OBJECT_CHANGED, listOf(push.objectId), push.revision)
            is RuntimePush.ObjectDeleted -> applied(event, WireNames.OBJECT_DELETED, listOf(push.objectId), push.revision)
            is RuntimePush.StateChanged -> applied(event, WireNames.STATE_CHANGE, listOf(push.modelElementId), push.revision)
        }
    }

    /**
     * Hands the object or stack push of [event] to the observation and journals it if the observation applied it. A
     * push that a snapshot already covers is not applied, and neither is a push that reveals a gap.
     */
    private suspend fun applied(event: ConnectionEvent.Received, messageType: String, ids: List<String>, revision: Long) {
        if (observer.handle(event)) {
            journal(SessionActivity.PushApplied(messageType, ids, revision, clock.elapsed()))
        }
    }

    /**
     * Takes the connection's new [state]. It shows the state, journals it and reports it to the conductor. After the
     * session was closed, it ignores the state.
     */
    private fun stateSeen(state: ConnectionState) {
        synchronized(lock) {
            if (sealed) return
            connectionState.value = state
        }
        journal(SessionActivity.ConnectionChanged(state, clock.elapsed()))
        conductor.offer(ConductorMessage.ConnectionChanged(state))
    }

    /** Takes a [notice] of the observation, on the router's coroutine. */
    private fun notice(notice: ObservationNotice) {
        when (notice) {
            is ObservationNotice.Synchronized -> journal(
                notice.reason?.let { SessionActivity.Synchronized(it, notice.revision, clock.elapsed()) }
                    ?: SessionActivity.Note("Read the model at revision ${notice.revision}.", clock.elapsed()),
            )
            is ObservationNotice.Anomaly -> journal(SessionActivity.Anomaly(notice.detail, clock.elapsed()))
            is ObservationNotice.OutOfSync -> conductor.offer(
                ConductorMessage.ObservationProblem(
                    DeployProblem(
                        Severity.WARNING,
                        DeployProblemCodes.OBSERVATION_OUT_OF_SYNC,
                        "Observation is on, but the model could not be read to get in step with the runtime: ${notice.reason} " +
                            "It stays as it was until the next pull.",
                        null,
                    ),
                ),
            )
        }
    }

    /** Records [activity] in the journal. */
    private fun journal(activity: SessionActivity) {
        journal.record(activity)
    }

    private companion object {
        /** The router's logger. */
        private val logger = LoggerFactory.getLogger(PushRouter::class.java)
    }
}
