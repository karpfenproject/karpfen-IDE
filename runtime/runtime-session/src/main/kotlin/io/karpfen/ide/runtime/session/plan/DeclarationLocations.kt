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

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.runtime.session.problem.SourceLocation
import java.nio.file.Path

/**
 * Where the objects, types and states of the uploaded files are declared, copied from the syntax index of each
 * analyzed file, so that a finding about an object, a type or a state points at its declaration without holding the
 * analysis. Immutable; the maps are copied.
 *
 * Each map goes from a file to the names declared in it and the range of each name in the file's analyzed text; of
 * equal names in one file the map holds whichever its builder chose (the planner keeps the first, the declaration an
 * editor navigates to). An object without a key is not in it.
 *
 * @param objects the object keys of each model file.
 * @param types the type names of each metamodel file.
 * @param states the state names of each state machine file.
 * @param snapshots the analyzed text of each file, which the ranges refer to.
 */
public class DeclarationLocations(
    objects: Map<Path, Map<String, TextRange>> = emptyMap(),
    types: Map<Path, Map<String, TextRange>> = emptyMap(),
    states: Map<Path, Map<String, TextRange>> = emptyMap(),
    snapshots: Map<Path, TextSnapshot> = emptyMap(),
) {
    /** The object keys per file. */
    private val objects: Map<Path, Map<String, TextRange>> = objects.mapValues { it.value.toMap() }

    /** The type names per file. */
    private val types: Map<Path, Map<String, TextRange>> = types.mapValues { it.value.toMap() }

    /** The state names per file. */
    private val states: Map<Path, Map<String, TextRange>> = states.mapValues { it.value.toMap() }

    /** The analyzed texts per file. */
    private val snapshots: Map<Path, TextSnapshot> = snapshots.toMap()

    /** The declaration of the object with [key] in the model [file], or null if the file declares none. */
    public fun objectIn(file: Path, key: String): SourceLocation? = locate(objects, file, key)

    /** The declaration of the type [name] in the metamodel [file], or null if the file declares none. */
    public fun typeIn(file: Path, name: String): SourceLocation? = locate(types, file, name)

    /** The declaration of the state [name] in the state machine [file], or null if the file declares none. */
    public fun stateIn(file: Path, name: String): SourceLocation? = locate(states, file, name)

    /** The file [file] as a whole, with its analyzed text if it has one. */
    public fun fileOf(file: Path): SourceLocation = SourceLocation(file, null, snapshots[file])

    /** The location of [name] in [file] according to [declarations]. */
    private fun locate(declarations: Map<Path, Map<String, TextRange>>, file: Path, name: String): SourceLocation? =
        declarations[file]?.get(name)?.let { range -> SourceLocation(file, range, snapshots[file]) }

    override fun toString(): String =
        "DeclarationLocations(objects=${objects.values.sumOf { it.size }}, types=${types.values.sumOf { it.size }}, " +
            "states=${states.values.sumOf { it.size }})"

    public companion object {
        /** No declarations at all. */
        public val EMPTY: DeclarationLocations = DeclarationLocations()
    }
}
