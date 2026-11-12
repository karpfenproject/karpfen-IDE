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
package io.karpfen.ide.notation.diagram.statechart

import io.karpfen.ide.notation.diagram.LampStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests that the diagram of a state machine holds what its text says. */
class StatechartProjectionTest {

    private val lamp = LampStateMachine.diagramOf(LampStateMachine.LAMP)

    @Test
    fun `the states keep their nesting, their order and what the text says about them`() {
        assertEquals("Lamp", lamp.attachedTo)
        assertEquals(listOf("off", "on", "broken"), lamp.states.map { it.name })
        assertEquals(listOf("full", "brighter"), lamp.state("on")?.children?.map { it.name })
        assertEquals(listOf("off", "on", "full", "brighter", "broken"), lamp.allStates().map { it.name })
        assertEquals(listOf("off"), lamp.allStates().filter { it.isInitial }.map { it.name })
        assertEquals(listOf("off", "on", "brighter"), lamp.allStates().filter { it.hasEntry }.map { it.name })
        assertTrue(lamp.state("on")!!.isComposite)
        assertEquals("on", LampStateMachine.LAMP.substring(lamp.state("on")!!.nameRange.start, lamp.state("on")!!.nameRange.end))
    }

    @Test
    fun `the transitions carry their states, their order and their condition as a label`() {
        assertEquals(
            listOf(
                "off -> full | switch/press",
                "on -> off | switch/press",
                "on -> brighter | switch/dim & event->bright & [\$(bulb->w\u2026",
                "off -> broken | service/smash",
                "off -> broken | ",
            ),
            lamp.transitions.map { "${it.sources.single()} -> ${it.targets.single()} | ${it.label}" },
        )
        assertEquals(listOf(1, 2, 3, 4, 5), lamp.transitions.map { it.priority })
        assertEquals(listOf("1: switch/press", "5"), listOf(lamp.transitions.first().caption, lamp.transitions.last().caption))
        assertEquals(listOf(false, false, true, false, false), lamp.transitions.map { it.notLooping })
        assertTrue(lamp.transitions.all { it.kind == TransitionKind.NORMAL })
    }

    @Test
    fun `transitions between the same states get ids that differ`() {
        assertEquals(
            listOf("NORMAL:off->full#1", "NORMAL:on->off#1", "NORMAL:on->brighter#1", "NORMAL:off->broken#1", "NORMAL:off->broken#2"),
            lamp.transitions.map { it.id },
        )
    }

    @Test
    fun `a split has several targets and a join several sources`() {
        val fork = LampStateMachine.diagramOf(LampStateMachine.FORK)

        val (split, join) = fork.transitions
        assertEquals(TransitionKind.SPLIT to (listOf("idle") to listOf("left", "right")), split.kind to (split.sources to split.targets))
        assertEquals(TransitionKind.JOIN to (listOf("left", "right") to listOf("done")), join.kind to (join.sources to join.targets))
        assertEquals("job/start", split.label)
    }

    @Test
    fun `a long condition is cut`() {
        val sum = "abcdefghij".map { "${'$'}($it)" }.joinToString(" + ")
        val text = LampStateMachine.FORK.replace("""EVENT("job", "start")""", "EVAL { return $sum > 1 }")

        val label = LampStateMachine.diagramOf(text).transitions.first().label

        assertEquals(40, label.length)
        assertTrue(label.endsWith("\u2026"))
    }

    @Test
    fun `a text without a state machine has no diagram`() {
        assertNull(StatechartProjection.of("", emptyList()))
    }
}
