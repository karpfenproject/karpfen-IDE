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
package io.karpfen.ide.ui.workbench.outline

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Tests what the Outline shows of the outline of a metamodel, a model, a state machine and a descriptor. */
class OutlineSelectionTest {

    @Test
    fun `of a metamodel only the types are shown`() {
        val outline = outlineOf(
            KarpfenLanguage.KMETA,
            """
            type "Bulb" "A bulb" { prop("watts", "number") }
            type "Lamp" "A lamp" { has("bulb", "Bulb") knows("control", "Bulb") }
            """.trimIndent(),
        )

        val shown = OutlineSelection.of(outline)

        assertEquals(listOf("Bulb" to emptyList(), "Lamp" to emptyList()), shown.nodes.map { it.name to it.children })
        assertSame(outline[0], shown.original(shown.nodes[0]), "a shown node stands for the node of the parse")
        assertEquals(outline[0].range, shown.nodes[0].range)
    }

    @Test
    fun `of a model only the objects are shown, each below the object that has it`() {
        val outline = outlineOf(
            KarpfenLanguage.KMODEL,
            """
            make object "room":"Room" {
                prop("size") -> "20"
                has("lamp") -> make object "lamp":"Lamp" {
                    has("bulb") -> make object "bulb":"Bulb" { prop("watts") -> "40" }
                    knows("control") -> "switch"
                }
                has("switch") -> make object "switch":"Switch" { }
            }
            """.trimIndent(),
        )

        val shown = OutlineSelection.of(outline)

        assertEquals(listOf("room"), shown.nodes.map { it.name })
        assertEquals(listOf("lamp", "switch"), shown.nodes.single().children.map { it.name })
        assertEquals(listOf("bulb"), shown.nodes.single().children.first().children.map { it.name })
        assertEquals(emptyList(), shown.nodes.single().children.first().children.single().children)
    }

    @Test
    fun `of a state machine only the states and the transitions are shown, at the top`() {
        val outline = outlineOf(
            KarpfenLanguage.KSTATES,
            """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { ENTRY { SET("mode", "off") } }
                    STATE "on" {
                        DO { SET("mode", "on") }
                        STATE "full" { }
                    }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "on" { CONDITION { EVENT("switch", "press") } }
                    SPLIT "off" -> "on", "full" { }
                    JOIN "on", "full" -> "off" { }
                }
                MACROS {
                    MACRO "half" { RETURNS("number") DEFINITION { EVAL { return 0.5 } } }
                }
            }
            """.trimIndent(),
        )

        val shown = OutlineSelection.of(outline)

        assertEquals(listOf("off", "on", "off -> on", "off -> on, full", "on, full -> off"), shown.nodes.map { it.name })
        assertEquals(listOf("full"), shown.nodes[1].children.map { it.name })
        assertTrue(all(shown.nodes).none { it.name == "ENTRY" || it.name == "DO" || it.name == "half" })
    }

    @Test
    fun `of a descriptor everything is shown, and the nodes are those of the parse`() {
        val outline = outlineOf(KarpfenLanguage.ENVIRONMENT, """{ "model": "lamp.kmodel", "stateMachines": [ { "file": "lamp.kstates", "attachedTo": "lamp" } ] }""")

        val shown = OutlineSelection.of(outline)

        assertEquals(outline, shown.nodes)
        assertTrue(shown.nodes.indices.all { shown.nodes[it] === outline[it] }, "nothing was copied")
    }

    private fun outlineOf(language: KarpfenLanguage, text: String): List<OutlineNode> {
        val outcome = defaultLanguageRegistry().support(language).parser.parse(TextSnapshot(text, 0))
        assertTrue(outcome.isSyntaxClean, "the text has syntax errors: ${outcome.syntaxDiagnostics}")
        return outcome.outline
    }

    private fun all(nodes: List<OutlineNode>): List<OutlineNode> = nodes.flatMap { listOf(it) + all(it.children) }
}
