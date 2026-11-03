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

/** The phases an environment can be in, without what the client knows about them. */
public enum class PhaseKind {
    /** Created and being configured. */
    CONFIGURING,

    /** Activated, not started. */
    ACTIVATED,

    /** Started; the engine runs. */
    RUNNING,

    /** Started, but the engine ended on its own. */
    HALTED,

    /** Stopped; final. */
    STOPPED,
}

/**
 * What a configuring environment has received so far, as far as the client knows: which texts were uploaded, which
 * values were set and how many observers and listeners were registered. Immutable.
 *
 * @property metamodel the domain metamodel was uploaded.
 * @property events the event definitions were uploaded.
 * @property model the model was uploaded.
 * @property machines the `attachedTo` keys of the uploaded state machines.
 * @property tickDelay the tick delay was set.
 * @property eventTtl the event time to live was set.
 * @property observers the number of object observers registered.
 * @property listeners the number of domain listeners registered.
 */
public data class Uploads(
    public val metamodel: Boolean,
    public val events: Boolean,
    public val model: Boolean,
    public val machines: Set<String>,
    public val tickDelay: Boolean,
    public val eventTtl: Boolean,
    public val observers: Int,
    public val listeners: Int,
) {
    public companion object {
        /** Nothing received yet: the uploads of a new environment. */
        public val NONE: Uploads = Uploads(
            metamodel = false,
            events = false,
            model = false,
            machines = emptySet(),
            tickDelay = false,
            eventTtl = false,
            observers = 0,
            listeners = 0,
        )
    }
}

/**
 * The lifecycle phase of an environment as the client keeps it: what the client's own successful calls and the
 * runtime's reports established, or what it could not establish. Immutable.
 */
public sealed interface EnvironmentPhase {
    /**
     * Created and being configured.
     *
     * @property uploads what it has received so far.
     */
    public data class Configuring(public val uploads: Uploads) : EnvironmentPhase

    /** Activated: the engine is built and frozen; it was not started. */
    public data object Activated : EnvironmentPhase

    /** Started; the engine runs. */
    public data object Running : EnvironmentPhase

    /** Started, but the engine ended on its own, as a snapshot revealed. */
    public data object Halted : EnvironmentPhase

    /** Stopped. Final: the client never restarts a stopped environment. */
    public data object Stopped : EnvironmentPhase

    /**
     * A call may or may not have been applied and the runtime could not be asked which; the next call that reads the
     * phase resolves it.
     *
     * @property candidates the phases the environment may be in.
     * @property reason why the phase is uncertain, in words.
     * @property uploads what it had received, as far as the client knows, in case it is still configuring.
     */
    public data class Uncertain(
        public val candidates: Set<PhaseKind>,
        public val reason: String,
        public val uploads: Uploads,
    ) : EnvironmentPhase

    /**
     * The runtime no longer knows the environment, for example because it restarted. Final.
     *
     * @property reason why the client concluded so, in words.
     */
    public data class Lost(public val reason: String) : EnvironmentPhase
}
