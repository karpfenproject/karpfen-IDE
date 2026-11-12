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
package io.karpfen.ide.notation.sync

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests that the diagram follows the text and survives the moments in which the text cannot be read. */
class StatechartTrackerTest {

    private val parser = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES).parser
    private val tracker = StatechartTracker()

    @Test
    fun `a text that reads without errors gives its diagram`() {
        val state = update(MACHINE, version = 0)

        assertFalse(state.isStale)
        assertEquals(listOf("off", "on"), state.diagram?.states?.map { it.name })
    }

    @Test
    fun `while the text has syntax errors the last diagram stays and is stale`() {
        update(MACHINE, version = 0)

        val broken = update(MACHINE.replace("STATE \"on\" { }", "STATE \"on\" {"), version = 1)

        assertTrue(broken.isStale)
        assertEquals(listOf("off", "on"), broken.diagram?.states?.map { it.name })

        val repaired = update(MACHINE.replace("\"on\"", "\"lit\""), version = 2)

        assertFalse(repaired.isStale)
        assertEquals(listOf("off", "lit"), repaired.diagram?.states?.map { it.name })
    }

    @Test
    fun `a parse that is behind the text changes nothing`() {
        val first = update(MACHINE, version = 0)
        val olderParse = parser.parse(TextSnapshot(MACHINE, 0))

        val typing = tracker.update(TextSnapshot(MACHINE + " ", 1), olderParse)

        assertEquals(first, typing)
    }

    @Test
    fun `before the first parse and for a text without a state machine there is no diagram`() {
        assertEquals(StatechartState.EMPTY, tracker.update(TextSnapshot(MACHINE, 0), null))
        assertNull(update("", version = 1).diagram)
    }

    private fun update(text: String, version: Long): StatechartState {
        val snapshot = TextSnapshot(text, version)
        val outcome: ParseOutcome = parser.parse(snapshot)
        return tracker.update(snapshot, outcome)
    }

    private companion object {
        val MACHINE = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    STATE "on" { }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "on" { }
                }
            }
        """.trimIndent()
    }
}
