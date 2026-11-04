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

import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.session.mirror.DifferenceKind
import io.karpfen.ide.runtime.session.mirror.MirrorChange
import io.karpfen.ide.runtime.session.mirror.MirrorComparison
import io.karpfen.ide.runtime.session.mirror.MirrorReducer
import io.karpfen.ide.runtime.session.mirror.MirrorSnapshot
import io.karpfen.ide.runtime.session.mirror.MirrorStep
import io.karpfen.ide.runtime.session.mirror.ObjectDifference
import io.karpfen.ide.runtime.session.states.ActiveStates
import io.karpfen.ide.runtime.session.states.ActiveStatesReducer

/**
 * The state of the observation: the observed model and the facts its rules need besides it.
 *
 * @property model the observed model.
 * @property lastRevision the revision of the last applied snapshot or push; 0 before anything was read.
 * @property hasRead true once a snapshot was applied.
 * @property stopped true once the environment stopped; the next snapshot is final.
 */
internal data class ObservationState(
    val model: ObservedModel,
    val lastRevision: Long,
    val hasRead: Boolean,
    val stopped: Boolean,
)

/** What the observation reacts to. */
internal sealed interface ObservationInput {
    /** The model subscription answered 200: observation is on. */
    data object SwitchedOn : ObservationInput

    /** The model unsubscription answered 200: observation is off. */
    data object SwitchedOff : ObservationInput

    /**
     * A push of the runtime arrived; only object and stack pushes concern the observation.
     *
     * @property push the push.
     */
    data class Pushed(val push: RuntimePush) : ObservationInput

    /**
     * A snapshot was read.
     *
     * @property snapshot the snapshot.
     */
    data class SnapshotRead(val snapshot: RuntimeModelSnapshot) : ObservationInput

    /**
     * Reading a snapshot failed.
     *
     * @property reason why, in a plain sentence.
     */
    data class SnapshotFailed(val reason: String) : ObservationInput

    /**
     * The connection opened; every open after the first is a reconnect.
     *
     * @property epoch the number of the open, starting at 1.
     */
    data class LinkOpened(val epoch: Int) : ObservationInput

    /** The connection was interrupted or ended; pushes may be lost until it opens again. */
    data object LinkInterrupted : ObservationInput

    /** The environment stopped; the runtime dropped the pushes still queued for it. */
    data object EnvironmentStopped : ObservationInput
}

/** What the observation asks its owner to do before the next input. */
internal sealed interface ObservationRequest {
    /** Subscribe to the model again, which re-verifies the WebSocket session after a reconnect. */
    data object Resubscribe : ObservationRequest

    /** Read a snapshot and feed it back. */
    data object ReadSnapshot : ObservationRequest
}

/**
 * The result of one input.
 *
 * @property state the state after the input.
 * @property update what changed in the model, or null if nothing was applied.
 * @property requests what the owner does next, in order, before it takes the next input.
 * @property anomalies what the input revealed that should not happen, in plain sentences.
 */
internal data class ObservationStep(
    val state: ObservationState,
    val update: ModelUpdate?,
    val requests: List<ObservationRequest>,
    val anomalies: List<String>,
)

/**
 * The rules of the observation switch, pure: every input answers the next state, the model update it caused, the
 * requests the owner executes before it takes the next input, and anomalies.
 *
 * | Input | Effect |
 * |---|---|
 * | switched on | mode on, synchronizing because it was switched on, read a snapshot |
 * | switched off | mode off, pulled at the last revision (predicted if nothing was read yet); the mirror keeps its state |
 * | object or stack push, mode off | ignored (pushes enqueued before the unsubscription may still arrive) |
 * | object or stack push, synchronizing | ignored (the pending snapshot is read later, so it covers the push) |
 * | object or stack push, live at L | at most L ignored; L + 1 applied, live at L + 1, an update; above L + 1 a gap: synchronizing, read a snapshot, an anomaly naming the missed revisions |
 * | snapshot read | when at least the last applied revision: mirror and states replaced, phase set, live when on, pulled when off, final after the stop; one update listing what differs from before. An older snapshot is ignored |
 * | snapshot failed | synchronizing because the snapshot failed when on; unchanged when off |
 * | connection opened again (not the first open), mode on | synchronizing because of the reconnect, subscribe again, then read a snapshot |
 * | connection interrupted, mode on | synchronizing because of the reconnect, no request (the next open brings them) |
 * | environment stopped | mode off, read a snapshot, which is final |
 *
 * After the stop, switching on or off changes nothing. Domain events, violations and unrecognized pushes are not the
 * observation's business.
 *
 * @param mirror applies snapshots and object pushes to the mirror.
 * @param states applies snapshots and stack pushes to the active states.
 */
internal class ObservationReducer(private val mirror: MirrorReducer, private val states: ActiveStatesReducer) {

    /** The state before anything was read: observation off, the [seed] as mirror and the predicted states. */
    fun initial(seed: MirrorSnapshot): ObservationState =
        ObservationState(
            model = ObservedModel(ObservationMode.OFF, SyncStatus.Predicted, null, seed, states.initial()),
            lastRevision = 0,
            hasRead = false,
            stopped = false,
        )

    /** The step [input] makes from [state]. */
    fun apply(state: ObservationState, input: ObservationInput): ObservationStep =
        when (input) {
            ObservationInput.SwitchedOn -> switchedOn(state)
            ObservationInput.SwitchedOff -> switchedOff(state)
            is ObservationInput.Pushed -> pushed(state, input.push)
            is ObservationInput.SnapshotRead -> snapshotRead(state, input.snapshot)
            is ObservationInput.SnapshotFailed -> snapshotFailed(state)
            is ObservationInput.LinkOpened -> linkOpened(state, input.epoch)
            ObservationInput.LinkInterrupted -> linkInterrupted(state)
            ObservationInput.EnvironmentStopped -> environmentStopped(state)
        }

    /** Observation switched on. */
    private fun switchedOn(state: ObservationState): ObservationStep {
        if (state.stopped) return unchanged(state, "Observation cannot be switched on: the environment stopped.")
        val model = state.model.copy(mode = ObservationMode.ON, sync = SyncStatus.Synchronizing(SyncReason.SWITCHED_ON))
        return ObservationStep(state.copy(model = model), null, listOf(ObservationRequest.ReadSnapshot), emptyList())
    }

    /** Observation switched off. */
    private fun switchedOff(state: ObservationState): ObservationStep {
        if (state.stopped) return unchanged(state)
        val model = state.model.copy(mode = ObservationMode.OFF, sync = offStatus(state))
        return ObservationStep(state.copy(model = model), null, emptyList(), emptyList())
    }

    /** A push arrived. */
    private fun pushed(state: ObservationState, push: RuntimePush): ObservationStep {
        val revision = when (push) {
            is RuntimePush.ObjectChanged -> push.revision
            is RuntimePush.ObjectDeleted -> push.revision
            is RuntimePush.StateChanged -> push.revision
            is RuntimePush.DomainEvent, is RuntimePush.Unrecognized -> return unchanged(state)
        }
        val sync = state.model.sync
        if (state.model.mode == ObservationMode.OFF || sync !is SyncStatus.Live || revision <= sync.revision) return unchanged(state)
        if (revision > sync.revision + 1) {
            val missed = if (revision == sync.revision + 2) "revision ${sync.revision + 1}" else "revisions ${sync.revision + 1} to ${revision - 1}"
            val model = state.model.copy(sync = SyncStatus.Synchronizing(SyncReason.GAP))
            return ObservationStep(
                state.copy(model = model),
                null,
                listOf(ObservationRequest.ReadSnapshot),
                listOf("A push with revision $revision arrived after revision ${sync.revision}: $missed did not arrive."),
            )
        }
        return applied(state, push, revision)
    }

    /** [state] after the object or stack [push] at the next [revision]. */
    private fun applied(state: ObservationState, push: RuntimePush, revision: Long): ObservationStep {
        val model = state.model
        val (next, update, anomalies) = when (push) {
            is RuntimePush.StateChanged -> {
                val states = states.reported(model.states, push.modelElementId, push.stack)
                Triple(model.copy(states = states), update(revision, MirrorChange.NONE, setOf(push.modelElementId)), emptyList())
            }
            is RuntimePush.ObjectChanged -> mirrorApplied(model, revision, mirror.changed(model.mirror, push))
            is RuntimePush.ObjectDeleted -> mirrorApplied(model, revision, mirror.deleted(model.mirror, push))
            is RuntimePush.DomainEvent, is RuntimePush.Unrecognized -> return unchanged(state)
        }
        val live = next.copy(sync = SyncStatus.Live(revision))
        return ObservationStep(state.copy(model = live, lastRevision = revision), update, emptyList(), anomalies)
    }

    /** The model after the mirror [step] of a push at [revision], its update and its anomalies. */
    private fun mirrorApplied(model: ObservedModel, revision: Long, step: MirrorStep): Triple<ObservedModel, ModelUpdate, List<String>> =
        Triple(
            model.copy(mirror = step.snapshot),
            update(revision, step.change, emptySet()),
            step.anomalies,
        )

    /** A snapshot was read. */
    private fun snapshotRead(state: ObservationState, snapshot: RuntimeModelSnapshot): ObservationStep {
        if (snapshot.revision < state.lastRevision) {
            return unchanged(state, "A snapshot at revision ${snapshot.revision} arrived after revision ${state.lastRevision} was applied; it is ignored.")
        }
        val before = state.model
        val step = mirror.replace(snapshot.roots, snapshot.revision)
        val states = states.replaced(before.states, snapshot.activeStates)
        val sync = when {
            state.stopped -> SyncStatus.Final(snapshot.revision)
            before.mode == ObservationMode.ON -> SyncStatus.Live(snapshot.revision)
            else -> SyncStatus.Pulled(snapshot.revision)
        }
        val model = before.copy(sync = sync, phase = snapshot.phase, mirror = step.snapshot, states = states)
        val differences = MirrorComparison.differences(before.mirror, step.snapshot)
        val update = ModelUpdate(
            revision = snapshot.revision,
            changed = idsOf(differences, DifferenceKind.DIFFERENT),
            created = idsOf(differences, DifferenceKind.UNEXPECTED),
            deleted = idsOf(differences, DifferenceKind.MISSING),
            machines = changedMachines(before.states, states),
            cause = UpdateCause.SNAPSHOT,
        )
        return ObservationStep(state.copy(model = model, lastRevision = snapshot.revision, hasRead = true), update, emptyList(), step.anomalies)
    }

    /** Reading a snapshot failed. */
    private fun snapshotFailed(state: ObservationState): ObservationStep {
        if (state.model.mode == ObservationMode.OFF) return unchanged(state)
        val model = state.model.copy(sync = SyncStatus.Synchronizing(SyncReason.SNAPSHOT_FAILED))
        return ObservationStep(state.copy(model = model), null, emptyList(), emptyList())
    }

    /** The connection opened. */
    private fun linkOpened(state: ObservationState, epoch: Int): ObservationStep {
        if (epoch <= 1 || state.model.mode == ObservationMode.OFF) return unchanged(state)
        val model = state.model.copy(sync = SyncStatus.Synchronizing(SyncReason.RECONNECTED))
        return ObservationStep(
            state.copy(model = model),
            null,
            listOf(ObservationRequest.Resubscribe, ObservationRequest.ReadSnapshot),
            emptyList(),
        )
    }

    /** The connection was interrupted. */
    private fun linkInterrupted(state: ObservationState): ObservationStep {
        if (state.model.mode == ObservationMode.OFF) return unchanged(state)
        val model = state.model.copy(sync = SyncStatus.Synchronizing(SyncReason.RECONNECTED))
        return ObservationStep(state.copy(model = model), null, emptyList(), emptyList())
    }

    /** The environment stopped. */
    private fun environmentStopped(state: ObservationState): ObservationStep {
        val model = state.model.copy(mode = ObservationMode.OFF, sync = offStatus(state))
        return ObservationStep(state.copy(model = model, stopped = true), null, listOf(ObservationRequest.ReadSnapshot), emptyList())
    }

    /** The status of a model that is not observed: pulled at the last revision, or predicted if nothing was read. */
    private fun offStatus(state: ObservationState): SyncStatus =
        if (state.hasRead) SyncStatus.Pulled(state.lastRevision) else SyncStatus.Predicted

    /** The step that leaves [state] as it is, with [anomalies]. */
    private fun unchanged(state: ObservationState, vararg anomalies: String): ObservationStep =
        ObservationStep(state, null, emptyList(), anomalies.toList())

    /** The update of a push at [revision] with the mirror [change] and the changed [machines]. */
    private fun update(revision: Long, change: MirrorChange, machines: Set<String>): ModelUpdate =
        ModelUpdate(revision, change.changed, change.created, change.deleted, machines, UpdateCause.PUSH)

    /** The ids of the objects among [differences] that differ as [kind] says, in order. */
    private fun idsOf(differences: List<ObjectDifference>, kind: DifferenceKind): Set<String> =
        differences.filter { it.kind == kind }.mapTo(LinkedHashSet()) { it.id }

    /** The attachment keys whose stack, origin or presence differs between [before] and [after]. */
    private fun changedMachines(before: ActiveStates, after: ActiveStates): Set<String> {
        val machines = (before.machines.keys + after.machines.keys).filterTo(LinkedHashSet()) { key ->
            val old = before.machines[key]
            val new = after.machines[key]
            old?.stack != new?.stack || old?.origin != new?.origin
        }
        (before.unknownElements.keys + after.unknownElements.keys)
            .filterTo(machines) { before.unknownElements[it] != after.unknownElements[it] }
        return machines
    }
}
