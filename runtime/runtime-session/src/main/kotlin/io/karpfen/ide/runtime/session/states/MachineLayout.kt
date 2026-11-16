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

import io.karpfen.dsl.states.InitialStates
import io.karpfen.dsl.states.StateMachine
import java.nio.file.Path

/**
 * What the IDE knows of one attached state machine before the runtime reports anything. That is its top-level states,
 * the stack the runtime will enter first and its pages. Immutable. It is built during the preparation of a deployment,
 * so nothing launched later holds the dsl-tools machine.
 *
 * The layout describes the state machine the runtime runs. For a state machine with container states that is the flat
 * state machine, in which the states of each fragment are copies named `<container>/<state>`. Its top-level states are
 * those of the text, since flattening only adds inner states.
 *
 * @property attachedTo the key of the object the machine runs attached to. It is the `modelElementId` of the machine's
 *   stack pushes.
 * @property file the machine's file.
 * @property topLevelStates the names of the machine's top-level states in file order.
 * @property initialStack the stack the runtime enters at the start, with flat names.
 * @property pages where the states and transitions of the run were written.
 */
public data class MachineLayout(
    public val attachedTo: String,
    public val file: Path,
    public val topLevelStates: List<String>,
    public val initialStack: List<String>,
    public val pages: MachinePages = MachinePages.NONE,
) {
    public companion object {
        /**
         * The layout of [machine] in [file], attached to [attachedTo]. [machine] is the state machine the runtime runs,
         * flat if the text has container states, and [pages] tells where its states were written.
         */
        public fun of(attachedTo: String, file: Path, machine: StateMachine, pages: MachinePages = MachinePages.NONE): MachineLayout =
            MachineLayout(attachedTo, file, machine.states.map { it.name }, InitialStackPredictor.predict(machine), pages)
    }
}

/**
 * What the IDE knows of one attachable state machine that a deployment hands to the environment as a definition. The
 * environment builds an instance of it for an object at the start, when a rule creates or exchanges an object, and each
 * instance starts in the same predicted stack. Immutable, and built during the preparation of a deployment like a
 * [MachineLayout], also from the flat state machine.
 *
 * @property name the name of the attachable state machine, which the runtime announces with each of its instances.
 * @property file the file of the attachable state machine.
 * @property topLevelStates the names of its top-level states in file order.
 * @property initialStack the stack every instance enters when it starts, with flat names.
 * @property pages where the states and transitions of its instances were written.
 */
public data class DefinitionLayout(
    public val name: String,
    public val file: Path,
    public val topLevelStates: List<String>,
    public val initialStack: List<String>,
    public val pages: MachinePages = MachinePages.NONE,
) {
    public companion object {
        /** The layout of the attachable [machine] named [name] in [file], flat if it has container states. */
        public fun of(name: String, file: Path, machine: StateMachine, pages: MachinePages = MachinePages.NONE): DefinitionLayout =
            DefinitionLayout(name, file, machine.states.map { it.name }, InitialStackPredictor.predict(machine), pages)
    }
}

/**
 * The first stack of a machine, as the runtime enters it. The runtime and dsl-tools share one rule for it,
 * `InitialStates.startPath`: at each level the state that is marked initial or holds the mark below it, else the
 * first state, and on below it. In a flat state machine that goes down into the copy of a fragment, so the stack
 * never ends on a container state. Stateless.
 */
internal object InitialStackPredictor {
    /** The initial stack of [machine]. It is empty for a machine without states. */
    fun predict(machine: StateMachine): List<String> = InitialStates.startPath(machine).map { it.name }
}
