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
package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.parse.FoldingKind
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.parse.SymbolKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [KstatesSyntaxAnalyzer]: declarations, references including those in EVAL bodies, outline, semantic spans and
 * folding of KStates, a state without a name, and the states, transitions and macros of the examples.
 */
class KstatesSyntaxAnalyzerTest {

    private val text = """
        STATEMACHINE ATTACHED TO "Robot" {
            STATES {
                INITIAL STATE "idle" {
                    ENTRY {
                        SET("speed", "0")
                        SETOBJ("self -> room", "target", MACRO("closest", "obstacles"))
                    }
                    STATE "inner" {
                        DO {
                            // first comment
                            // second comment
                            IF IN SCOPE("target", "room") {
                                WITH MACRO("dist", "position") AS "d" {
                                    SET("distance", EVAL {
                                        x = {"a":
                                             1}
                                        return ${'$'}(d) + @dist(${'$'}( a -> b ))
                                    })
                                }
                            }
                            EVENT("public", "tick")
                            DROPOBJ("target")
                        }
                    }
                }
                STATE "busy" { }
            }
            TRANSITIONS {
                TRANSITION "idle" -> "busy" NOT LOOPING {
                    CONDITION {
                        EVENT("public", "go")
                        VALUE("flag")
                    }
                }
                SPLIT "busy" -> "idle", "inner" { }
                JOIN "idle", "inner" -> "busy" { }
            }
            MACROS {
                MACRO "dist" {
                    TAKES("a", "Vector")
                    TAKES("all", list("Obstacle"))
                    RETURNS(reference("Obstacle"))
                    DEFINITION { EVAL { return 1 } }
                }
            }
        }
    """.trimIndent()

    private val analysis = analyze(KarpfenLanguage.KSTATES, text)
    private val index = analysis.index

    @Test
    fun `the state machine, states, bindings, macros and parameters are declared`() {
        assertEquals(emptyList(), analysis.syntaxDiagnostics)
        assertEquals(
            listOf(
                Triple(SymbolKind.STATEMACHINE, "STATEMACHINE", null),
                Triple(SymbolKind.STATE, "idle", null),
                Triple(SymbolKind.STATE, "inner", "idle"),
                Triple(SymbolKind.BINDING, "d", "inner"),
                Triple(SymbolKind.STATE, "busy", null),
                Triple(SymbolKind.MACRO, "dist", null),
                Triple(SymbolKind.MACRO_PARAMETER, "a", "dist"),
                Triple(SymbolKind.MACRO_PARAMETER, "all", "dist"),
            ),
            index.declarations.map { Triple(it.kind, it.name, it.container) },
        )
        assertEquals(
            listOf("Robot", null, null, null, null, "reference(Obstacle)", "Vector", "list(Obstacle)"),
            index.declarations.map { it.typeName },
        )
        assertEquals("STATEMACHINE", text.at(index.declarationsOf(SymbolKind.STATEMACHINE).single().nameRange))
        assertEquals(listOf("idle", "inner"), index.enclosingDeclarations(text.indexOf("DROPOBJ")).filter { it.kind == SymbolKind.STATE }.map { it.name })
    }

    @Test
    fun `references cover types, actions, conditions, transitions, macros and EVAL bodies`() {
        fun refs(kind: ReferenceKind) = index.referencesOf(kind).map { it.name to it.qualifier }

        assertEquals(listOf("Robot" to null, "Vector" to null, "Obstacle" to null, "Obstacle" to null), refs(ReferenceKind.TYPE))
        assertEquals(
            listOf("speed", "self->room", "obstacles", "target", "room", "position", "distance", "d", "a->b", "target", "flag").map { it to null },
            refs(ReferenceKind.MODEL_PATH),
        )
        assertEquals(listOf("target" to "self->room"), refs(ReferenceKind.RELATION_KEY))
        assertEquals(listOf("closest", "dist", "dist").map { it to null }, refs(ReferenceKind.MACRO))
        assertEquals(listOf("public" to null, "public" to null), refs(ReferenceKind.EVENT_DOMAIN))
        assertEquals(listOf("tick" to "public", "go" to "public"), refs(ReferenceKind.EVENT_NAME))
        assertEquals(
            listOf("idle", "busy", "busy", "idle", "inner", "idle", "inner", "busy"),
            index.referencesOf(ReferenceKind.STATE).map { it.name },
        )
        val path = index.references(ReferenceKind.MODEL_PATH, "a->b").single()
        assertEquals("a -> b", text.at(path.range), "EVAL paths: the inner range without blanks around it")
        assertEquals("self -> room", text.at(index.references(ReferenceKind.MODEL_PATH, "self->room").single().range))
        assertEquals("dist", text.at(index.referencesOf(ReferenceKind.MACRO)[2].range), "the name after @")
    }

    @Test
    fun `the outline has the machine with states, blocks, bindings, transitions and macros`() {
        val machine = analysis.outline.single()
        assertEquals(SymbolKind.STATEMACHINE to "Robot", machine.kind to machine.detail)
        assertEquals(
            listOf("idle", "busy", "idle -> busy", "busy -> idle, inner", "idle, inner -> busy", "dist"),
            machine.children.map { it.name },
        )
        val idle = machine.children[0]
        assertEquals(listOf(SymbolKind.ENTRY_BLOCK to "ENTRY", SymbolKind.STATE to "inner"), idle.children.map { it.kind to it.name })
        assertEquals("ENTRY", text.at(idle.children[0].selectionRange))
        val doBlock = idle.children[1].children.single()
        assertEquals(SymbolKind.DO_BLOCK, doBlock.kind)
        val binding = doBlock.children.single()
        assertEquals(Triple(SymbolKind.BINDING, "d", "dist"), Triple(binding.kind, binding.name, binding.detail))
        assertEquals(
            listOf(SymbolKind.TRANSITION, SymbolKind.SPLIT, SymbolKind.JOIN),
            machine.children.subList(2, 5).map { it.kind },
        )
        assertEquals("\"idle\" -> \"busy\"", text.at(machine.children[2].selectionRange))
        val macro = machine.children[5]
        assertEquals("reference(Obstacle)", macro.detail)
        assertEquals(listOf("a" to "Vector", "all" to "list(Obstacle)"), macro.children.map { it.name to it.detail })
    }

    @Test
    fun `semantic spans include EVAL references and binding names`() {
        val spans = analysis.semanticSpans.map { it.kind to text.at(it.range) }
        assertTrue(SemanticKind.TYPE_NAME to "Robot" in spans)
        assertTrue(SemanticKind.STATE_NAME to "inner" in spans)
        assertTrue(SemanticKind.BINDING_NAME to "d" in spans)
        assertTrue(SemanticKind.BINDING_NAME to "all" in spans, "macro parameters are bound names")
        assertTrue(SemanticKind.PROPERTY_NAME to "target" in spans)
        assertTrue(SemanticKind.EVENT_DOMAIN to "public" in spans && SemanticKind.EVENT_NAME to "tick" in spans)
        assertTrue(SemanticKind.MODEL_PATH to "a -> b" in spans && SemanticKind.MACRO_NAME to "dist" in spans)
        assertTrue(spans.none { it.second == "0" }, "string right sides are values")
    }

    @Test
    fun `blocks fold outside EVAL bodies, multi-line bodies fold as embedded code`() {
        val folds = analysis.folding.map { it.kind to text.at(it.range).lineSequence().first().trim() }
        val evalBody = text.indexOf("EVAL {")
        val embedded = analysis.folding.single { it.kind == FoldingKind.EMBEDDED_CODE }
        assertEquals(text.indexOf('{', evalBody), embedded.range.start)
        assertTrue(text.at(embedded.range).endsWith("}"))
        assertTrue(analysis.folding.none { it.kind == FoldingKind.BLOCK && it.range.start > evalBody && it.range.start < embedded.range.end })
        assertEquals(listOf(FoldingKind.COMMENT to "// first comment"), folds.filter { it.first == FoldingKind.COMMENT })
        assertTrue(FoldingKind.BLOCK to "{" in folds)
        assertEquals(listOf("{"), folds.filter { it.first == FoldingKind.EMBEDDED_CODE }.map { it.second })
    }

    @Test
    fun `a state without a name keeps its place and its nested states`() {
        val broken = "STATEMACHINE ATTACHED TO \"T\" {\n STATES {\n  STATE {\n   STATE \"child\" { }\n  }\n }\n TRANSITIONS { }\n}"

        val result = analyze(KarpfenLanguage.KSTATES, broken)

        assertTrue(result.syntaxDiagnostics.isNotEmpty())
        val states = result.index.declarationsOf(SymbolKind.STATE)
        assertEquals(listOf("" to null, "child" to ""), states.map { it.name to it.container })
        assertTrue(states[0].nameRange.isEmpty && states[0].nameRange.start == broken.indexOf("STATE {"))
        assertEquals(listOf("child"), result.outline.single().children.single().children.map { it.name })
        checkInvariants(broken, result, "nameless state")
    }

    @Test
    fun `the examples yield the expected states, transitions and macros`() {
        val robot = analyze(KarpfenLanguage.KSTATES, Examples.file("karpfen-runtime/single_robot_example/cleaning_robot.kstates").text)
        assertEquals(
            listOf("ready", "observe", "react to obstacle", "react to wall", "drive", "drive fast", "drive slow"),
            robot.index.declarationsOf(SymbolKind.STATE).map { it.name },
        )
        assertEquals("observe", robot.index.declarations(SymbolKind.STATE, "react to wall").single().container)
        assertEquals(7, robot.outline.single().children.count { it.kind == SymbolKind.TRANSITION })
        assertEquals(7, robot.index.declarationsOf(SymbolKind.MACRO).size)
        assertEquals("Robot", robot.index.declarationsOf(SymbolKind.STATEMACHINE).single().typeName)

        val car = analyze(KarpfenLanguage.KSTATES, Examples.file("karpfen-runtime/autonomous_car_example/autonomous_car.kstates").text)
        val transitions = car.outline.single().children
        assertEquals(
            listOf("off -> checkBattery, checkBrakes, calibrateSensors", "batteryReady, brakesReady, sensorsReady -> ready"),
            transitions.filter { it.kind == SymbolKind.SPLIT || it.kind == SymbolKind.JOIN }.map { it.name },
        )

        val swarm = analyze(KarpfenLanguage.KSTATES, Examples.file("karpfen-runtime/drone_swarm_example/drone_swarm.kstates").text)
        assertEquals(
            listOf("drones", "leader", "watchlist", "condemned").map { it to "self" },
            swarm.index.referencesOf(ReferenceKind.RELATION_KEY).map { it.name to it.qualifier },
        )
        assertTrue(swarm.index.references(ReferenceKind.MODEL_PATH, "condemned").isNotEmpty())
    }
}
