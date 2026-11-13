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
package io.karpfen.ide.ui.workbench.runtime

import io.karpfen.ide.runtime.session.states.ActiveStates
import io.karpfen.ide.ui.diagram.DiagramLive
import java.nio.file.Path

/**
 * Tells what a run adds to the diagram of a file. The statechart of a state machine file gets the states its machine
 * is in. Every other file gets nothing.
 */
internal object DiagramLives {

    /**
     * What the run with the active [states] adds to the diagram of [file], or null if no state machine of the run
     * comes from that file. A state machine that is attached to several objects shows the states of all of them.
     */
    fun of(states: ActiveStates, file: Path): DiagramLive? {
        val machines = states.machines.values.filter { it.file.toAbsolutePath().normalize() == file.toAbsolutePath().normalize() }
        return if (machines.isEmpty()) null else DiagramLive(active = machines.flatMap { it.stack }.toSet())
    }
}
