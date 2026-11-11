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

import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.session.mirror.MirrorSnapshot
import io.karpfen.ide.runtime.session.states.ActiveStates

/** The position of the observation switch. */
public enum class ObservationMode {
    /** There is no observation traffic. The model changes only when it is pulled. */
    OFF,

    /**
     * The runtime pushes every object and stack change. The changes are applied in revision order after a first
     * snapshot.
     */
    ON,
}

/** Why the observed model is being re-read from a snapshot. */
public enum class SyncReason {
    /** Observation was switched on, and this is the first snapshot after subscribing. */
    SWITCHED_ON,

    /** A push skipped revisions, so pushes were lost. */
    GAP,

    /**
     * The connection was interrupted or opened again, so pushes may have been lost meanwhile. The model is live again
     * only once the connection is open and a snapshot was read after that. A pull meanwhile updates the model but
     * leaves it synchronizing. So does every pull after the connection gave up for good.
     */
    RECONNECTED,

    /** Reading a snapshot failed. It is tried again. */
    SNAPSHOT_FAILED,
}

/** How far the observed model is in step with the runtime. */
public sealed interface SyncStatus {
    /** Nothing was read from the runtime yet. The mirror is the seed and the states are predictions. */
    public data object Predicted : SyncStatus

    /**
     * Observation is off and the model is as of the last pull.
     *
     * @property revision the revision of the pulled snapshot.
     */
    public data class Pulled(public val revision: Long) : SyncStatus

    /**
     * Observation is on, but the model is not in step with the pushes. A snapshot is pending, or the connection has to
     * open again first. Pushes are not applied meanwhile, because the snapshot that follows covers them.
     *
     * @property reason why the model is re-read.
     */
    public data class Synchronizing(public val reason: SyncReason) : SyncStatus

    /**
     * Observation is on and every revision up to [revision] is applied.
     *
     * @property revision the last applied revision.
     */
    public data class Live(public val revision: Long) : SyncStatus

    /**
     * The model as read after the environment stopped. It no longer changes.
     *
     * @property revision the revision of the final snapshot.
     */
    public data class Final(public val revision: Long) : SyncStatus
}

/**
 * What the IDE shows of a running environment. Immutable.
 *
 * @property mode the position of the observation switch.
 * @property sync how far the model is in step with the runtime.
 * @property phase the phase of the environment as the last snapshot reported it, or null before the first snapshot.
 * @property mirror the model as values.
 * @property states the active state configurations of the attached machines.
 */
public data class ObservedModel(
    public val mode: ObservationMode,
    public val sync: SyncStatus,
    public val phase: RuntimePhase?,
    public val mirror: MirrorSnapshot,
    public val states: ActiveStates,
)

/** What brought a [ModelUpdate]. */
public enum class UpdateCause {
    /** A snapshot replaced the model. */
    SNAPSHOT,

    /** A push of the runtime was applied. */
    PUSH,
}

/**
 * One change of the observed model, for views that animate or highlight what changed.
 *
 * @property revision the revision the model is at after the change.
 * @property changed objects that existed before and changed.
 * @property created objects that are new.
 * @property deleted objects that are gone.
 * @property machines the attachment keys of the machines whose stack changed.
 * @property cause whether a snapshot or a push brought the change.
 */
public data class ModelUpdate(
    public val revision: Long,
    public val changed: Set<String>,
    public val created: Set<String>,
    public val deleted: Set<String>,
    public val machines: Set<String>,
    public val cause: UpdateCause,
)
