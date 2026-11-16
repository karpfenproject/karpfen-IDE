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
package io.karpfen.ide.runtime.session.states

import io.karpfen.dsl.states.State
import io.karpfen.dsl.states.StateMachine
import java.nio.file.Path

/**
 * What the IDE knows of one attached state machine before the runtime reports anything. That is its top-level states
 * and the stack the runtime will enter first. Immutable. It is built during the preparation of a deployment, so
 * nothing launched later holds the dsl-tools machine.
 *
 * @property attachedTo the key of the object the machine runs attached to. It is the `modelElementId` of the machine's
 *   stack pushes.
 * @property file the machine's file.
 * @property topLevelStates the names of the machine's top-level states in file order.
 * @property initialStack the stack the runtime enters at the start.
 */
public data class MachineLayout(
    public val attachedTo: String,
    public val file: Path,
    public val topLevelStates: List<String>,
    public val initialStack: List<String>,
) {
    public companion object {
        /** The layout of [machine] in [file], attached to [attachedTo]. */
        public fun of(attachedTo: String, file: Path, machine: StateMachine): MachineLayout =
            MachineLayout(attachedTo, file, machine.states.map { it.name }, InitialStackPredictor.predict(machine.states))
    }
}

/**
 * What the IDE knows of one attachable state machine that a deployment hands to the environment as a definition. The
 * environment builds an instance of it for an object at the start, when a rule creates or exchanges an object, and each
 * instance starts in the same predicted stack. Immutable, and built during the preparation of a deployment like a
 * [MachineLayout].
 *
 * @property name the name of the attachable state machine, which the runtime announces with each of its instances.
 * @property file the file of the attachable state machine.
 * @property topLevelStates the names of its top-level states in file order.
 * @property initialStack the stack every instance enters when it starts.
 */
public data class DefinitionLayout(
    public val name: String,
    public val file: Path,
    public val topLevelStates: List<String>,
    public val initialStack: List<String>,
) {
    public companion object {
        /** The layout of the attachable [machine] named [name] in [file]. */
        public fun of(name: String, file: Path, machine: StateMachine): DefinitionLayout =
            DefinitionLayout(name, file, machine.states.map { it.name }, InitialStackPredictor.predict(machine.states))
    }
}

/**
 * The runtime's rule for the first stack of a machine, as in `StateMachineQueryHelper.getInitialStateStack`. At every
 * level it enters the state marked initial, or else the first state, and goes on recursively into its inner states.
 * Stateless.
 */
internal object InitialStackPredictor {
    /** The initial stack of a machine whose top-level states are [states]. It is empty for a machine without states. */
    fun predict(states: List<State>): List<String> {
        val stack = ArrayList<String>()
        var level = states
        while (level.isNotEmpty()) {
            val entered = level.firstOrNull { it.isInitial } ?: level.first()
            stack += entered.name
            level = entered.innerStates
        }
        return stack
    }
}
