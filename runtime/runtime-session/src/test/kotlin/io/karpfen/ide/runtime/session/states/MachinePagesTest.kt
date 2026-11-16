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

import io.karpfen.ide.core.document.dsl.DslFlattener
import io.karpfen.ide.core.document.dsl.Flattening
import io.karpfen.ide.runtime.session.examples.DslTexts
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Covers [MachinePages] and the initial stack of a flat state machine. A lamp whose two container states use one
 * fragment has two pages in the fragment's file. Each page sees its own states and transitions under the names of the
 * fragment's text, the state machine itself sees its own, and a name or index of another page is not seen at all. The
 * predicted initial stack goes through an initial container state into the fragment's initial state, with flat names.
 */
class MachinePagesTest {

    private val lampFile: Path = Path.of("/project/lamp.kstates")
    private val testFile: Path = Path.of("/project/bulb_test.kstates")

    @Test
    fun `one fragment used twice is two pages in its file`() {
        val pages = pagesOf(TWO_USES)

        assertEquals(listOf("first", "second"), pages.pagesIn(testFile))
        assertEquals(emptyList(), pages.pagesIn(lampFile))
    }

    @Test
    fun `each page sees its own states under their local names`() {
        val pages = pagesOf(TWO_USES)

        assertEquals("lit", pages.localState("first/lit", "first"))
        assertEquals("lit", pages.localState("second/lit", "second"))
        assertNull(pages.localState("first/lit", "second"))
        assertNull(pages.localState("first/lit", null))
        assertEquals("first", pages.localState("first", null))
        assertNull(pages.localState("first", "first"))
        assertEquals(listOf("lit"), pages.localStates(listOf("first", "first/lit"), "first"))
        assertEquals(listOf("first"), pages.localStates(listOf("first", "first/lit"), null))
        assertEquals(emptyList(), pages.localStates(listOf("first", "first/lit"), "second"))
    }

    @Test
    fun `a name the pages do not know belongs to the state machine itself`() {
        val pages = pagesOf(TWO_USES)

        assertEquals("elsewhere", pages.localState("elsewhere", null))
        assertNull(pages.localState("elsewhere", "first"))
        assertEquals("anything", MachinePages.NONE.localState("anything", null))
    }

    @Test
    fun `each page sees its own transitions by the index of its text`() {
        val pages = pagesOf(TWO_USES)

        assertEquals(0, pages.localTransition(0, null))
        assertEquals(1, pages.localTransition(1, null))
        assertNull(pages.localTransition(2, null))
        assertEquals(0, pages.localTransition(2, "first"))
        assertEquals(1, pages.localTransition(3, "first"))
        assertEquals(0, pages.localTransition(4, "second"))
        assertNull(pages.localTransition(4, "first"))
        assertNull(pages.localTransition(0, "first"))
        assertEquals(-1, pages.localTransition(-1, null))
        assertEquals(-1, pages.localTransition(-1, "first"))
        assertEquals(-1, pages.localTransition(99, "second"))
    }

    @Test
    fun `the initial stack goes through an initial container state into the fragment with flat names`() {
        val flat = flatten(INITIAL_CONTAINER)

        assertEquals(listOf("test", "test/lit"), InitialStackPredictor.predict(flat.machine))
        val layout = MachineLayout.of("lamp", lampFile, flat.machine, MachinePages.of(lampFile, flat, mapOf("bulb test" to testFile)))
        assertEquals(listOf("test", "test/lit"), layout.initialStack)
        assertEquals(listOf("lit"), layout.pages.localStates(layout.initialStack, "test"))
    }

    /** The pages of the lamp [host] with the fragment `bulb test` in [testFile]. */
    private fun pagesOf(host: String): MachinePages = MachinePages.of(lampFile, flatten(host), mapOf("bulb test" to testFile))

    /** The lamp [host] flattened with the fragment `bulb test`. */
    private fun flatten(host: String): Flattening.Flat =
        assertIs<Flattening.Flat>(DslFlattener().flatten(DslTexts.machine(host), listOf(DslTexts.machine(BULB_TEST))))

    private companion object {
        /** The fragment `bulb test`: from `lit` to `dim`, then into its final state. */
        val BULB_TEST = """
            STATEMACHINE "bulb test" FRAGMENT {
                STATES {
                    INITIAL STATE "lit" { }
                    STATE "dim" { }
                    FINAL STATE "passed" { }
                }
                TRANSITIONS {
                    TRANSITION "lit" -> "dim" { }
                    TRANSITION "dim" -> "passed" { }
                }
            }
        """.trimIndent()

        /** A lamp that runs the fragment in `first` and then in `second`. */
        val TWO_USES = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    CONTAINER STATE "first" USE "bulb test" { }
                    CONTAINER STATE "second" USE "bulb test" { }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "first" { }
                    TRANSITION "first" -> "second" {
                        CONDITION { DONE }
                    }
                }
            }
        """.trimIndent()

        /** A lamp that starts in its container state `test`. */
        val INITIAL_CONTAINER = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL CONTAINER STATE "test" USE "bulb test" { }
                    STATE "off" { }
                }
                TRANSITIONS {
                    TRANSITION "test" -> "off" {
                        CONDITION { DONE }
                    }
                }
            }
        """.trimIndent()
    }
}
