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

/**
 * Splits the flat stack the runtime pushes into its parallel branches. The runtime concatenates the paths of all
 * branches without a separator, and every branch path starts at a top-level state. So a new branch begins at every
 * name of a top-level state. Names before the first top-level name form a branch of their own.
 *
 * The split is ambiguous when a nested state has the same name as a top-level state. The runtime's state names are
 * global, and its lookups take the first depth-first match. The nested state then starts a branch here too.
 *
 * @param topLevelStates the names of the machine's top-level states.
 */
internal class StackSegmenter(topLevelStates: List<String>) {
    /** The names at which a branch begins. */
    private val starts: Set<String> = topLevelStates.toSet()

    /** The branches of [stack] in order. An empty stack has no branch. */
    fun regions(stack: List<String>): List<List<String>> {
        val regions = ArrayList<List<String>>()
        var current = ArrayList<String>()
        stack.forEach { name ->
            if (name in starts && current.isNotEmpty()) {
                regions += current
                current = ArrayList()
            }
            current += name
        }
        if (current.isNotEmpty()) regions += current
        return regions
    }
}
