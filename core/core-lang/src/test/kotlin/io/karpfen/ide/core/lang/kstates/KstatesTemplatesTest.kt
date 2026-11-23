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
package io.karpfen.ide.core.lang.kstates

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.fixtures.ExamplePaths
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.lang.fixtures.ServicesProbe
import io.karpfen.ide.core.lang.fixtures.caret
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the KStates templates of whole constructs: where each is offered, the feature switches they follow, their
 * placeholders in visiting order, their ranking after the one-line snippets, and that dsl-tools reads each of them
 * without findings once it stands in a fitting text, at top level and nested. The EVAL body of the macro template is
 * only parsed, never run.
 */
class KstatesTemplatesTest {

    private val probe = ServicesProbe()
    private val off = ServicesProbe(ExampleProject.analysis.copy(languageFeatures = emptySet()))
    private val reader = DslReader()
    private val robotPath = ExampleProject.pathOf(ExamplePaths.ROBOT_KSTATES)

    /** The templates [services] offer at the caret of [textWithCaret], sorted like the list for an empty filter. */
    private fun templates(textWithCaret: String, services: ServicesProbe = probe): List<CompletionItem> {
        val (text, offset) = caret(textWithCaret)
        return services.complete(ExampleProject.document(robotPath, text, version = 3), offset)
            .filter { it.detail == "template" }
            .sortedWith(compareBy({ it.sortKey }, { it.label }))
    }

    /** The template whose label starts with [prefix] at the caret of [textWithCaret]. */
    private fun template(textWithCaret: String, prefix: String): CompletionItem = templates(textWithCaret).single { it.label.startsWith(prefix) }

    /** The texts of the placeholders of [item], in visiting order. */
    private fun placeholders(item: CompletionItem): List<String> = item.placeholders.map { item.insertText.substring(it.start, it.end) }

    /** Fails unless dsl-tools reads [text] with every language feature on without any finding. */
    private fun assertReads(text: String) {
        assertEquals(emptyList(), reader.kstates(TextSnapshot(text, 0), KstatesFeature.entries.toSet()).diagnostics.map { it.message }, text)
    }

    /** A state machine attached to `Robot` with the [states] and [transitions] given, and [macros] if not null. */
    private fun machine(states: String, transitions: String = "", macros: String? = null): String =
        "STATEMACHINE ATTACHED TO \"Robot\" {\n STATES {\n$states\n }\n TRANSITIONS {\n$transitions\n }\n" +
            (macros?.let { " MACROS {\n$it\n }\n" } ?: "") + "}\n"

    @Test
    fun `the top level offers a whole state machine and a whole fragment`() {
        val machine = template("‸", "STATEMACHINE ATTACHED TO")
        val fragment = template("‸", "STATEMACHINE \"…\" FRAGMENT")

        assertEquals(
            """
            STATEMACHINE ATTACHED TO "Type" {
                STATES {
                    INITIAL STATE "Idle" { }
                    STATE "Active" { }
                }
                TRANSITIONS {
                    TRANSITION "Idle" -> "Active" {
                        CONDITION {
                            EVENT("domain", "event")
                        }
                    }
                }
            }
            """.trimIndent(),
            machine.insertText,
        )
        assertEquals(listOf("Type", "Idle", "Active", "Idle", "Active", "domain", "event"), placeholders(machine))
        assertEquals(listOf("fragment", "Working", "Finished", "Working", "Finished", "domain", "event"), placeholders(fragment))
        assertTrue("FINAL STATE \"Finished\" { }" in fragment.insertText)
        assertReads(machine.insertText)
        assertReads(fragment.insertText)
    }

    @Test
    fun `a state with ENTRY and DO and a container state go where states go, at top level and nested`() {
        val inStates = machine("  INITIAL STATE \"Idle\" { }\n  ‸")
        val inState = machine("  INITIAL STATE \"Idle\" {\n   ‸\n  }")
        val state = template(inStates, "STATE \"…\" with")
        val container = template(inStates, "CONTAINER STATE")

        assertEquals(
            "STATE \"Name\" {\n    ENTRY {\n        SET(\"path\", \"value\")\n    }\n    DO {\n    }\n}",
            state.insertText,
        )
        assertEquals(listOf("Name", "path", "value"), placeholders(state))
        assertEquals("CONTAINER STATE \"Working\" USE \"fragment\" {\n}", container.insertText)
        assertEquals(listOf("Working", "fragment"), placeholders(container))
        assertEquals(listOf(state, container).map { it.label }.sorted(), templates(inState).map { it.label }.sorted())
        for (text in listOf(inStates, inState)) {
            assertReads(text.replace("‸", state.insertText))
            assertReads(text.replace("‸", container.insertText))
        }
    }

    @Test
    fun `a transition, a split and a join go where transitions go`() {
        val states = "  INITIAL STATE \"Idle\" { }\n  STATE \"Active\" { }\n  STATE \"Left\" { }\n  STATE \"Right\" { }\n  STATE \"Joined\" { }"
        val text = machine(states, "  ‸")
        val transition = template(text, "TRANSITION")
        val split = template(text, "SPLIT")
        val join = template(text, "JOIN")

        assertEquals("TRANSITION \"Idle\" -> \"Active\" {\n    CONDITION {\n        EVENT(\"domain\", \"event\")\n    }\n}", transition.insertText)
        assertEquals(listOf("Idle", "Active", "domain", "event"), placeholders(transition))
        assertEquals(listOf("Idle", "Left", "Right", "domain", "event"), placeholders(split))
        assertEquals("JOIN \"Left\", \"Right\" -> \"Joined\" {\n}", join.insertText)
        assertEquals(listOf("Left", "Right", "Joined"), placeholders(join))
        assertReads(text.replace("‸", transition.insertText))
        val unknownTarget = text.replace("‸", transition.insertText.replace("\"Active\" {", "\"Missing\" {"))
        assertTrue(reader.kstates(TextSnapshot(unknownTarget, 0)).diagnostics.isNotEmpty(), "the read check finds a wrong name")
        assertReads(text.replace("‸", split.insertText))
        assertReads(text.replace("‸", join.insertText))
    }

    @Test
    fun `a macro has a parameter, a result type and a definition that only returns`() {
        val text = machine("  INITIAL STATE \"Idle\" { }", macros = "  ‸")
        val macro = template(text, "MACRO")

        assertEquals(
            "MACRO \"name\" {\n    TAKES(\"value\", \"number\")\n    RETURNS(\"number\")\n    DEFINITION {\n        EVAL {\n" +
                "            return \$(value)\n        }\n    }\n}",
            macro.insertText,
        )
        assertEquals(listOf("name", "value", "number", "number", "value"), placeholders(macro))
        assertReads(text.replace("‸", macro.insertText))
    }

    @Test
    fun `templates follow the feature switches of their one-line snippets`() {
        val transitions = machine("  INITIAL STATE \"Idle\" { }", "  ‸")
        val macros = machine("  INITIAL STATE \"Idle\" { }", macros = "  ‸")

        assertEquals(listOf("TRANSITION"), templates(transitions, off).map { it.label.substringBefore(' ') })
        assertEquals(listOf("JOIN", "SPLIT", "TRANSITION"), templates(transitions).map { it.label.substringBefore(' ') }.sorted())
        assertEquals(emptyList(), templates(macros, off))
        assertEquals(listOf("CONTAINER", "STATE"), templates(machine("  ‸"), off).map { it.label.substringBefore(' ') })
        assertEquals(2, templates("‸", off).size, "the state machine and the fragment need no feature")
    }

    @Test
    fun `templates rank after every one-line snippet of their position`() {
        val (text, offset) = caret(machine("  ‸"))
        val items = probe.complete(ExampleProject.document(robotPath, text, version = 3), offset).sortedBy { it.sortKey }

        assertEquals(listOf("template", "template"), items.takeLast(2).map { it.detail })
        assertTrue(items.dropLast(2).none { it.detail == "template" })
    }
}
