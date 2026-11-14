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
package io.karpfen.ide.ui.diagram

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.statechart.StatechartProjection
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Covers the texts a statechart writes, which the canvas measures before the layout: the title line of the state
 * machine is measured in the style of the names of states, for an attached and for an attachable state machine.
 */
class StatechartPainterTest {

    @Test
    fun `the title of an attached state machine is measured with the names of the states`() {
        val titles = StatechartPainter.texts(diagramOf("STATEMACHINE ATTACHED TO \"Lamp\" { STATES { INITIAL STATE \"off\" { } } }"))

        assertTrue(titles.getValue(TextRole.TITLE).containsAll(listOf("off", "ATTACHED TO Lamp")), "$titles")
    }

    @Test
    fun `the title of an attachable state machine is measured with the names of the states`() {
        val source = "STATEMACHINE \"glowing\" ATTACHABLE TO \"Lamp\", \"Bulb\" { STATES { INITIAL STATE \"dim\" { } } }"

        val titles = StatechartPainter.texts(diagramOf(source))

        assertTrue(titles.getValue(TextRole.TITLE).containsAll(listOf("dim", "glowing ATTACHABLE TO Lamp, Bulb")), "$titles")
    }

    private fun diagramOf(source: String): StatechartDiagram {
        val outcome = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES).parser.parse(TextSnapshot(source, 0))
        return checkNotNull(StatechartProjection.of(source, outcome.outline))
    }
}
