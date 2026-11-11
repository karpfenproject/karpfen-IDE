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
package io.karpfen.ide.runtime.protocol.message

import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey

/**
 * The lifecycle phase of an environment as the runtime derives and reports it in a snapshot.
 *
 * @property wireName the phase as the snapshot writes it.
 */
public enum class RuntimePhase(public val wireName: String) {
    /** Created and being configured. There is no execution thread yet. */
    CONFIGURING("configuring"),

    /** Activated. The engine is built over the uploaded texts but was never started. */
    ACTIVATED("activated"),

    /**
     * The engine thread is alive. The environment was started, or a start or a stop is under way. A stopped
     * environment is reported as running while the engine's last tick ends. If a call in that tick never returns, it
     * stays reported as running for good.
     */
    RUNNING("running"),

    /** Started and not stopped, but the engine thread ended on its own. The runtime's console log has the reason. */
    HALTED("halted"),

    /** Stopped, and the engine's last tick ended. The final model stays readable. */
    STOPPED("stopped"),
    ;

    public companion object {
        /** The phase whose [wireName] is [name], or null for any other text. */
        public fun ofWireName(name: String): RuntimePhase? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * The model of an environment as the runtime reported it at one revision, read between two ticks. It contains exactly
 * the effects of every observation up to [revision] and nothing after it. Values of this type are immutable.
 *
 * @property environment the environment.
 * @property phase the lifecycle phase the runtime derived.
 * @property revision the revision of the last observation the snapshot contains. It is 0 before anything happened.
 * @property activeStates the last reported active stack of each state machine, keyed by the machine's `attachedTo`
 *   key. The machines come in the order they first reported. The map is empty before the first tick after a start.
 * @property roots every root object of the model in model order, each as an `objectChanged` push writes its value.
 * @throws IllegalArgumentException if [revision] is negative.
 */
public data class RuntimeModelSnapshot(
    public val environment: EnvironmentKey,
    public val phase: RuntimePhase,
    public val revision: Long,
    public val activeStates: Map<String, List<String>>,
    public val roots: List<WireValue.WireObject>,
) {
    init {
        require(revision >= 0) { "a snapshot's revision is never negative, not $revision" }
    }
}
