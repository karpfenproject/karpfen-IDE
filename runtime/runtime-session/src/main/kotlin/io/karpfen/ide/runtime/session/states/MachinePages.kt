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

import io.karpfen.dsl.states.JoinTransition
import io.karpfen.dsl.states.SplitTransition
import io.karpfen.dsl.states.Transition
import io.karpfen.dsl.states.TransitionLike
import io.karpfen.ide.core.document.dsl.Flattening
import java.nio.file.Path

/**
 * Where a state of a running state machine was written. Immutable.
 *
 * @property file the file whose text declares the state: the file of the state machine itself, or the file of the
 *   fragment it was copied from.
 * @property localName the name of the state in that text.
 * @property page the flat name of the container state whose copy of a fragment holds the state, or null for a state of
 *   the state machine itself. Two uses of one fragment are two pages.
 */
public data class StatePlace(public val file: Path, public val localName: String, public val page: String?)

/**
 * The pages of one running state machine: the state machine itself and every use of a fragment by one of its container
 * states. The runtime runs the flat state machine, in which the copy of a fragment inside the container state `self
 * test` names its states `self test/lit`, and it reports those names. A diagram shows one file, with the names written
 * in it. These pages translate between the two: which pages a file is, and which states and transitions of the run
 * belong to one page, under their local names.
 *
 * A page is named by the flat name of its container state, and the state machine itself is the page null. A machine
 * without container states has only that page, and every name is its own local name.
 *
 * The transitions the engine names by their index count the transitions of the flat state machine. The transitions of
 * the state machine itself come first with the indices of its text, and the copied transitions of each use of a
 * fragment follow in one block, in the order of the fragment's text. So an index of a page's block, less the start of
 * that block, is the index in the fragment's text. Immutable.
 *
 * @param places the place of every state of the flat state machine, by its flat name.
 * @param transitionPages the page of every transition of the flat state machine, by its index.
 */
public class MachinePages internal constructor(
    private val places: Map<String, StatePlace>,
    private val transitionPages: List<String?>,
) {
    /** The first index of each page's block of transitions. */
    private val blockStarts: Map<String?, Int> = LinkedHashMap<String?, Int>().also { starts ->
        transitionPages.forEachIndexed { index, page -> starts.putIfAbsent(page, index) }
    }

    /**
     * The pages whose states are written in [file], in the order of the states. The state machine itself is not among
     * them: its file is the file of its run.
     */
    public fun pagesIn(file: Path): List<String> {
        val target = file.toAbsolutePath().normalize()
        return places.values.filter { it.page != null && it.file.toAbsolutePath().normalize() == target }.mapNotNull { it.page }.distinct()
    }

    /**
     * The local name of the state [name] of the run if it belongs to [page], and null otherwise. A name the pages do
     * not know belongs to the state machine itself, where it keeps its name.
     */
    public fun localState(name: String, page: String?): String? {
        val place = places[name] ?: return name.takeIf { page == null }
        return place.localName.takeIf { place.page == page }
    }

    /** The local names of the states of [stack] that belong to [page], in the order of the stack. */
    public fun localStates(stack: List<String>, page: String?): List<String> = stack.mapNotNull { localState(it, page) }

    /**
     * The index in the text of [page] of the transition with the flat [index], or null if that transition belongs to
     * another page. An index outside the flat transitions, such as -1 for a transition the engine made up, stays as it
     * is for the state machine itself and is -1 for a fragment, so that the diagram finds the transition by its states.
     */
    public fun localTransition(index: Int, page: String?): Int? {
        if (index !in transitionPages.indices) return if (page == null) index else -1
        if (transitionPages[index] != page) return null
        return index - blockStarts.getValue(page)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is MachinePages && places == other.places && transitionPages == other.transitionPages)

    override fun hashCode(): Int = 31 * places.hashCode() + transitionPages.hashCode()

    override fun toString(): String = "MachinePages(pages=${places.values.mapNotNull { it.page }.distinct()})"

    public companion object {
        /** The pages of a state machine the IDE could not flatten: only the machine itself, every name its own. */
        public val NONE: MachinePages = MachinePages(emptyMap(), emptyList())

        /**
         * The pages of the state machine in [file] that [flat] holds flattened, whose fragments are written in the
         * files [fragmentFiles] names by fragment name.
         */
        public fun of(file: Path, flat: Flattening.Flat, fragmentFiles: Map<String, Path>): MachinePages {
            val places = flat.origins.mapValues { (_, origin) ->
                val written = origin.fragment?.let { fragmentFiles[it] } ?: file
                StatePlace(written, origin.localName, origin.container)
            }
            val transitions = flat.machine.transitions.map { transition -> sourceOf(transition)?.let { places[it]?.page } }
            return MachinePages(places, transitions)
        }

        /** The first state [transition] leaves, which names the page it was written in. */
        private fun sourceOf(transition: TransitionLike): String? = when (transition) {
            is Transition -> transition.fromState
            is SplitTransition -> transition.fromState
            is JoinTransition -> transition.fromStates.firstOrNull()
        }
    }
}
