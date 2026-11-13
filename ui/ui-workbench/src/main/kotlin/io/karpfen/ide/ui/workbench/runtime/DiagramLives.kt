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

import io.karpfen.ide.runtime.session.mirror.MirrorSnapshot
import io.karpfen.ide.runtime.session.mirror.PropertyValue
import io.karpfen.ide.runtime.session.mirror.ScalarValue
import io.karpfen.ide.runtime.session.states.ActiveStates
import io.karpfen.ide.ui.diagram.DiagramLive
import java.nio.file.Path

/**
 * Tells what a run adds to the diagram of a file. The diagram of a state machine file gets the states its machine is
 * in. The diagram of the model file gets the values its objects have now. Every other file gets nothing.
 */
internal object DiagramLives {

    /**
     * What the run with the active [states] and the model [mirror], which was started from [modelFile], adds to the
     * diagram of [file], or null if the run has nothing to do with that file. A state machine that is attached to
     * several objects shows the states of all of them.
     */
    fun of(states: ActiveStates, mirror: MirrorSnapshot, modelFile: Path, file: Path): DiagramLive? {
        val machines = states.machines.values.filter { same(it.file, file) }
        return when {
            machines.isNotEmpty() -> DiagramLive(active = machines.flatMap { it.stack }.toSet())
            same(modelFile, file) -> DiagramLive(
                values = mirror.objects.mapValues { (_, item) -> item.properties.map { it.key to text(it.value) } },
            )
            else -> null
        }
    }

    private fun same(first: Path, second: Path): Boolean = first.toAbsolutePath().normalize() == second.toAbsolutePath().normalize()

    private fun text(value: PropertyValue): String = when (value) {
        is PropertyValue.Single -> text(value.value)
        is PropertyValue.Many -> value.values.joinToString(", ", "[", "]", transform = ::text)
    }

    /** [value] as a row shows it. A whole number is written without a fraction. */
    private fun text(value: ScalarValue): String = when (value) {
        is ScalarValue.Number -> if (value.value % 1.0 == 0.0 && kotlin.math.abs(value.value) < WHOLE_LIMIT) value.value.toLong().toString() else value.value.toString()
        is ScalarValue.Text -> value.value
        is ScalarValue.Flag -> value.value.toString()
    }

    /** Above this size a number is written as the runtime gives it, also when it has no fraction. */
    private const val WHOLE_LIMIT = 1e15
}
