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
package io.karpfen.ide.runtime.session.plan

import io.karpfen.dsl.states.State
import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.dsl.DslFlattener
import io.karpfen.ide.core.document.dsl.Flattening
import java.nio.file.Path

/**
 * The compositions of the state machines of one context of a deployment. The runtime flattens every attached and every
 * attachable state machine with the fragments it holds when it activates the environment, and it holds exactly the
 * state machines the descriptor lists. So each composition here is flattened with the state machines of the context,
 * through the same flattener of dsl-tools, and what fails here fails in the runtime.
 *
 * Each state machine is flattened at most once, on first use. Confined to one planning or preparation call, because it
 * reads dsl-tools models and caches its results without synchronization.
 *
 * @param machines the state machines of the context as dsl-tools read them, by file.
 * @param flattener flattens a state machine with its fragments.
 */
internal class Compositions(private val machines: Map<Path, StateMachine>, private val flattener: DslFlattener) {

    /** The results so far, by file. */
    private val flattened = HashMap<Path, Flattening>()

    /** The flattening of the state machine in [file], or null if the context has no read state machine there. */
    fun of(file: Path): Flattening? {
        val machine = machines[file] ?: return null
        return flattened.getOrPut(file) { flattener.flatten(machine, machines.values) }
    }

    /** The file of [machine], one of the read state machines of the context, compared by identity. */
    fun fileOf(machine: StateMachine): Path? = machines.entries.firstOrNull { it.value === machine }?.key

    /**
     * The files of the fragments of the context by their names. Where two files name the same fragment, the first one
     * wins, as in the flattener.
     */
    fun fragmentFiles(): Map<String, Path> {
        val files = LinkedHashMap<String, Path>()
        machines.forEach { (file, machine) ->
            val name = machine.name
            if (machine.isFragment && name != null) files.putIfAbsent(name, file)
        }
        return files
    }

    companion object {
        /** The names of the fragments that the container states of [machine] use, at any depth, each once, in text order. */
        fun usedFragments(machine: StateMachine): List<String> =
            flattened(machine.states).mapNotNull { it.fragment?.takeIf(String::isNotEmpty) }.distinct()

        /** [states] with all their inner states, depth first. */
        private fun flattened(states: List<State>): List<State> = states.flatMap { listOf(it) + flattened(it.innerStates) }
    }
}
