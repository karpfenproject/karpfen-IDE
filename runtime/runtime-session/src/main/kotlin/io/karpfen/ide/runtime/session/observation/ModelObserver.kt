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
package io.karpfen.ide.runtime.session.observation

import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.connection.ConnectionEvent
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.session.mirror.MirrorSnapshot
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory

/**
 * The live switch of one session. It owns the [ObservedModel] and the stream of [ModelUpdate]s. It feeds the
 * [ObservationReducer] with connection events and with the outcomes of its own runtime calls. It executes the
 * reducer's requests through the [ObservationPort].
 *
 * The observer is confined to one coroutine, which is the session's router. Its functions are called one at a time,
 * and it is the single writer of [model] and [updates]. Every function executes the requests its input caused, and
 * feeds their results back, before it returns. So the caller takes the next connection event only afterwards. Pushes
 * that arrive meanwhile wait in the caller's unbounded inbox. The runtime also guarantees two things. The subscription
 * is in place before the snapshot is read, and pushes arrive in revision order. Together this is why switching on, a
 * gap and a reconnect neither lose nor reorder updates. Publishing never suspends, so the observer never waits for the
 * collectors of [updates].
 *
 * - [switchOn] subscribes. The runtime may not have processed the WebSocket handshake yet, and then it refuses because
 *   it has no open session for the client. Such a refusal is retried, with [ObservationSettings.subscribeAttempts]
 *   attempts in all, [ObservationSettings.subscribeRetryDelay] apart. Then it switches on and reads the first
 *   snapshot.
 * - Snapshots that the reducer requests while observation is on are read up to [ObservationSettings.snapshotAttempts]
 *   times in all, [ObservationSettings.snapshotRetryDelay] apart. If none succeeds, the listener hears
 *   [ObservationNotice.OutOfSync], and the mirror stays as it was until the next pull. A subscription that the reducer
 *   requests after a reconnect uses the subscription's retries. If it fails, the listener hears the same notice and
 *   the snapshot is not read.
 * - [pull] and the final read after the stop read one snapshot, once.
 *
 * @param reducer the rules.
 * @param port the runtime calls.
 * @param seed the mirror before anything was read, which is the seed of the deployed model.
 * @param settings the retries and how far a collector of the updates may fall behind.
 * @param listener hears synchronizations, out-of-sync reports and anomalies.
 */
internal class ModelObserver(
    private val reducer: ObservationReducer,
    private val port: ObservationPort,
    seed: MirrorSnapshot,
    private val settings: ObservationSettings,
    private val listener: ObservationListener,
) {
    /** The current state of the rules. */
    private var state: ObservationState = reducer.initial(seed)

    /** The writable observed model. */
    private val modelState = MutableStateFlow(state.model)

    /** The writable stream of updates. */
    private val updateStream = MutableSharedFlow<ModelUpdate>(
        extraBufferCapacity = settings.updateBuffer,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The observed model. It starts with the seed, observation off and the predicted states. */
    val model: StateFlow<ObservedModel> = modelState.asStateFlow()

    /**
     * Every change of the observed model, hot and without replay. The observer never waits for a collector. A
     * collector that falls more than [ObservationSettings.updateBuffer] updates behind misses the oldest of them, while
     * [model] always shows the latest state.
     */
    val updates: Flow<ModelUpdate> = updateStream.asSharedFlow()

    /**
     * Takes one event of the connection. Returns true if it was an object or stack push that was applied to the model.
     * An opening after the first one, an interruption and an end make the observation re-synchronize while it is on.
     * The rules apply object and stack pushes, unless a snapshot covers them or they reveal a gap. Other pushes and
     * protocol violations are not the observation's business.
     */
    suspend fun handle(event: ConnectionEvent): Boolean {
        val input = when (event) {
            is ConnectionEvent.Opened -> ObservationInput.LinkOpened(event.epoch)
            is ConnectionEvent.Interrupted, is ConnectionEvent.Ended -> ObservationInput.LinkInterrupted
            is ConnectionEvent.Received -> when (event.push) {
                is RuntimePush.ObjectChanged, is RuntimePush.ObjectDeleted, is RuntimePush.StateChanged -> ObservationInput.Pushed(event.push)
                is RuntimePush.DomainEvent, is RuntimePush.Unrecognized -> return false
            }
            is ConnectionEvent.Violated -> return false
        }
        val step = advance(input)
        execute(step)
        return step.update?.cause == UpdateCause.PUSH
    }

    /**
     * Switches observation on. It subscribes, with retries while the runtime has no session for the client. Then it
     * reads the first snapshot and returns its outcome. A failed subscription is returned as it is and leaves
     * observation off.
     */
    suspend fun switchOn(): CallOutcome<RuntimeModelSnapshot> {
        when (val subscribed = subscribe()) {
            is CallOutcome.Done -> Unit
            is CallOutcome.Refused -> return subscribed
            is CallOutcome.Failed -> return subscribed
        }
        return feed(ObservationInput.SwitchedOn) ?: readSnapshot(attempts = 1)
    }

    /**
     * Switches observation off. A failed unsubscription is returned as it is and leaves observation as it was.
     */
    suspend fun switchOff(): CallOutcome<Unit> {
        val outcome = port.unsubscribe()
        if (outcome is CallOutcome.Done) feed(ObservationInput.SwitchedOff)
        return outcome
    }

    /**
     * Reads one snapshot and applies it, whether observation is on or off. A failed pull while the model is live leaves
     * it live, because the mirror is still complete up to its revision and the pushes after it keep coming.
     */
    suspend fun pull(): CallOutcome<RuntimeModelSnapshot> {
        val outcome = port.snapshot()
        if (outcome !is CallOutcome.Done && state.model.sync is SyncStatus.Live) return outcome
        return applySnapshot(outcome, attempts = 1)
    }

    /**
     * Settles the model after the environment stopped. It switches observation off, reads the final snapshot once and
     * returns its outcome. The runtime dropped the pushes still queued for the environment, so only the snapshot shows
     * its final state. A failed read leaves the model as it was until the next pull.
     */
    suspend fun environmentStopped(): CallOutcome<RuntimeModelSnapshot> =
        feed(ObservationInput.EnvironmentStopped) ?: readSnapshot(attempts = 1)

    /**
     * Applies [input], publishes the result and executes the requests it caused. Returns the outcome of the last
     * snapshot read among them, or null if none was read.
     */
    private suspend fun feed(input: ObservationInput): CallOutcome<RuntimeModelSnapshot>? = execute(advance(input))

    /** Applies [input] to the rules and publishes the step it makes, without executing its requests. */
    private fun advance(input: ObservationInput): ObservationStep = reducer.apply(state, input).also(::publish)

    /**
     * Executes the requests of [step] and feeds their results back. Returns the outcome of the last snapshot read
     * among them, or null if none was read. A failed resubscription reports the model out of sync and skips the
     * remaining requests.
     */
    private suspend fun execute(step: ObservationStep): CallOutcome<RuntimeModelSnapshot>? {
        var snapshot: CallOutcome<RuntimeModelSnapshot>? = null
        for (request in step.requests) {
            when (request) {
                ObservationRequest.Resubscribe -> {
                    val subscribed = subscribe()
                    if (subscribed !is CallOutcome.Done) {
                        outOfSync("subscribing again after the reconnect failed: ${describe(subscribed)}")
                        return snapshot
                    }
                }
                ObservationRequest.ReadSnapshot -> snapshot = readSnapshot(settings.snapshotAttempts)
            }
        }
        return snapshot
    }

    /**
     * Reads a snapshot up to [attempts] times while observation is on, and once while it is off. Every outcome is fed
     * to the rules. Returns the last outcome.
     */
    private suspend fun readSnapshot(attempts: Int): CallOutcome<RuntimeModelSnapshot> = applySnapshot(port.snapshot(), attempts)

    /**
     * Feeds the [outcome] of a first read, and of the reads after it, to the rules. A snapshot is applied and reported
     * as a synchronization. If the model is live afterwards, the report carries the reason the observation was
     * synchronizing for. Otherwise it is reported as a plain read. After a failure while observation is on, the
     * snapshot is read again after the retry delay, until [attempts] reads were made. Then the model is reported out of
     * sync. Returns the last outcome.
     */
    private suspend fun applySnapshot(outcome: CallOutcome<RuntimeModelSnapshot>, attempts: Int): CallOutcome<RuntimeModelSnapshot> {
        val reason = (state.model.sync as? SyncStatus.Synchronizing)?.reason
        var current = outcome
        var number = 1
        while (true) {
            when (val read = current) {
                is CallOutcome.Done -> {
                    feed(ObservationInput.SnapshotRead(read.value))
                    if (state.lastRevision == read.value.revision) {
                        val live = state.model.sync is SyncStatus.Live
                        listener.notice(ObservationNotice.Synchronized(reason.takeIf { live }, read.value.revision))
                    }
                    return read
                }
                is CallOutcome.Refused, is CallOutcome.Failed -> {
                    val description = describe(read)
                    feed(ObservationInput.SnapshotFailed(description))
                    val on = state.model.mode == ObservationMode.ON
                    if (!on || number >= attempts) {
                        if (on) outOfSync("no snapshot could be read in ${if (number == 1) "one attempt" else "$number attempts"}: $description")
                        return read
                    }
                    number++
                    delay(settings.snapshotRetryDelay)
                    current = port.snapshot()
                }
            }
        }
    }

    /** Subscribes, retrying while the runtime has no open WebSocket session for the client. */
    private suspend fun subscribe(): CallOutcome<Unit> {
        var attempt = 1
        while (true) {
            val outcome = port.subscribe()
            if (!lacksSession(outcome) || attempt >= settings.subscribeAttempts) return outcome
            attempt++
            delay(settings.subscribeRetryDelay)
        }
    }

    /**
     * Takes over the state of [step], publishes the model and the update, and reports the anomalies. It never suspends,
     * because the update stream drops the oldest update that a slow collector has not taken yet.
     */
    private fun publish(step: ObservationStep) {
        state = step.state
        modelState.value = step.state.model
        step.anomalies.forEach { listener.notice(ObservationNotice.Anomaly(it)) }
        step.update?.let { updateStream.tryEmit(it) }
    }

    /** Reports that observation is on but out of sync because [reason]. */
    private fun outOfSync(reason: String) {
        logger.warn("Observation is out of sync: {}", reason)
        listener.notice(ObservationNotice.OutOfSync(reason))
    }

    private companion object {
        /** The observer's logger. */
        private val logger = LoggerFactory.getLogger(ModelObserver::class.java)

        /** True if [outcome] is the runtime's refusal because the client has no open WebSocket session. */
        fun lacksSession(outcome: CallOutcome<*>): Boolean {
            val failure = (outcome as? CallOutcome.Failed)?.failure
            return failure is RuntimeFailure.Rejected && failure.kind == RejectionKind.NO_SESSION
        }

        /** [outcome], which failed, in a plain sentence. */
        fun describe(outcome: CallOutcome<*>): String =
            when (outcome) {
                is CallOutcome.Done -> "it succeeded"
                is CallOutcome.Refused -> outcome.refusal.reason
                is CallOutcome.Failed -> outcome.failure.describe()
            }
    }
}
