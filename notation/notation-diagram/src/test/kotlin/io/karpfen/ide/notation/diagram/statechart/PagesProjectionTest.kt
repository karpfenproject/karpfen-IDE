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
import io.karpfen.ide.notation.diagram.layout.StatechartMetrics
import io.karpfen.ide.notation.diagram.layout.TextMeasurer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests that the diagram of a state machine holds its pages: fragments, container states, final states and `DONE`. */
class PagesProjectionTest {

    private val fragment = LampStateMachine.diagramOf(LampStateMachine.BULB_TEST)
    private val paged = LampStateMachine.diagramOf(LampStateMachine.PAGED)

    @Test
    fun `a fragment is headed by its name and the word FRAGMENT and has no type`() {
        assertTrue(fragment.isFragment)
        assertEquals("bulb test", fragment.name)
        assertEquals(emptyList(), fragment.types)
        assertNull(fragment.attachedTo)
        assertEquals("bulb test FRAGMENT", fragment.title)
        assertFalse(paged.isFragment)
        assertEquals("ATTACHED TO Lamp", paged.title)
    }

    @Test
    fun `final states are marked and keep their breakpoints`() {
        assertEquals(listOf("passed", "failed"), fragment.allStates().filter { it.isFinal }.map { it.name })
        assertTrue(fragment.state("passed")!!.entryBreakpoint)
        assertTrue(fragment.allStates().none { it.isContainer })
    }

    @Test
    fun `a container state names its fragment at any depth and keeps its history, initial mark and tick limit`() {
        val selfTest = paged.state("self test")!!
        val check = paged.state("check")!!

        assertEquals(listOf("bulb test", "switch test"), listOf(selfTest.fragment, check.fragment))
        assertTrue(selfTest.isContainer && !selfTest.isComposite && !selfTest.isFinal)
        assertEquals(StateHistory.SHALLOW, selfTest.history)
        assertEquals("off", selfTest.timeout?.target)
        assertTrue(check.isInitial)
        val range = selfTest.fragmentRange!!
        assertEquals("bulb test", LampStateMachine.PAGED.substring(range.start, range.end))
        assertNull(paged.state("off")!!.fragment)
    }

    @Test
    fun `DONE reads as a clause of the label and is found in the condition`() {
        val labels = paged.transitions.map { it.label }
        assertEquals(listOf("service/test", "DONE", "switch/press", "DONE & switch/press"), labels)
        assertEquals(listOf(false, true, false, true), paged.transitions.map { it.isDone })
        val done = paged.transitions[3].doneRange!!
        assertEquals("DONE", LampStateMachine.PAGED.substring(done.start, done.end))
    }

    @Test
    fun `a container state is as high as a state with one more row, and as wide as the name of its fragment`() {
        val selfTest = paged.state("self test")!!
        val off = paged.state("off")!!
        val measurer = TextMeasurer.estimating()

        assertEquals(StatechartMetrics.leafHeight(off) + StatechartMetrics.PAGE_LINE + StatechartMetrics.BLOCK_LINE, StatechartMetrics.leafHeight(selfTest))
        assertTrue(StatechartMetrics.leafWidth(selfTest, measurer) >= StatechartMetrics.pageWidth(selfTest, measurer) + 2 * StatechartMetrics.NAME_PADDING)
        assertEquals(0f, StatechartMetrics.pageWidth(off, measurer))
    }
}
