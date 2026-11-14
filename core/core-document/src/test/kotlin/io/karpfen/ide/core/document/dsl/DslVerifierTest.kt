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
package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The adapter over the project verifier of dsl-tools on real reads: findings across files in the coordinates of the
 * state machine they concern, the attached object handed to the verifier, and nothing for reads that keep no dsl-tools
 * result.
 */
class DslVerifierTest {

    private val reader: DslReader = DslReader()
    private val verifier: DslVerifier = DslVerifier()

    private val metamodel = reader.kmeta(TextSnapshot(METAMODEL, 0))
    private val model = reader.kmodel(TextSnapshot(MODEL, 0), assertNotNull(metamodel.model))
    private val machine = reader.kstates(TextSnapshot(MACHINE, 0))

    @Test
    fun `the reads of a project are verified, with each finding in the text of its state machine`() {
        val findings = verifier.verify(VerificationInput(metamodel, null, model, listOf(MachineToVerify(machine, "lamp")))).stateMachines.single()

        val unknown = findings.single { it.code == "project.unknown-path" }
        assertEquals(Severity.ERROR, unknown.severity)
        assertEquals(DiagnosticSource.DSL_TOOLS, unknown.source)
        assertTrue(MACHINE.substring(unknown.range.start, unknown.range.end).contains("colour"), "${unknown.range}")
        val unreachable = findings.single { it.code == "project.unreachable-state" }
        assertEquals(Severity.WARNING, unreachable.severity)
        assertTrue(MACHINE.substring(unreachable.range.start, unreachable.range.end).contains("lost"), "${unreachable.range}")
        assertEquals(findings.sortedWith(Diagnostic.ORDER), findings)
    }

    @Test
    fun `the attached object reaches the verifier, which checks its type`() {
        val onSwitch = verifier.verify(VerificationInput(metamodel, null, model, listOf(MachineToVerify(machine, "wall")))).stateMachines.single()
        val withoutObject = verifier.verify(VerificationInput(metamodel, null, model, listOf(MachineToVerify(machine, null)))).stateMachines.single()

        assertEquals(listOf(Severity.ERROR), onSwitch.filter { it.code == ATTACHED_OBJECT_TYPE }.map { it.severity })
        assertTrue(withoutObject.none { it.code == ATTACHED_OBJECT_TYPE }, "$withoutObject")
    }

    @Test
    fun `one list of findings answers each state machine, in order`() {
        val clean = reader.kstates(TextSnapshot(CLEAN_MACHINE, 0))
        val findings = verifier.verify(
            VerificationInput(metamodel, null, model, listOf(MachineToVerify(clean, "lamp"), MachineToVerify(machine, "lamp"))),
        ).stateMachines

        assertEquals(2, findings.size)
        assertEquals(emptyList(), findings[0])
        assertTrue(findings[1].isNotEmpty())
    }

    @Test
    fun `reads built by hand keep nothing to verify and get no findings`() {
        val byHand = ReadResult<StateMachine>(machine.model, machine.diagnostics)

        assertEquals(
            listOf(emptyList()),
            verifier.verify(VerificationInput(metamodel, null, model, listOf(MachineToVerify(byHand, "lamp")))).stateMachines,
        )
        assertEquals(
            listOf(emptyList()),
            verifier.verify(VerificationInput(ReadResult(metamodel.model, metamodel.diagnostics), null, model, listOf(MachineToVerify(machine, "lamp")))).stateMachines,
            "without the metamodel's own read no machine is verified",
        )
        assertEquals(machine, byHand, "a read equals another of the same model and diagnostics")
    }

    private companion object {
        /** The code of an attached object of another type than the machine is attached to. */
        const val ATTACHED_OBJECT_TYPE = "project.attached-object-type"

        val METAMODEL = """
            type "Lamp" "l" {
                prop("mode", "string")
            }
            type "Switch" "s" {
                prop("position", "string")
            }
            type "Room" "r" {
                has("lamp", "Lamp")
                has("switch", "Switch")
            }
        """.trimIndent() + "\n"

        val MODEL = """
            make object "room":"Room" {
                has("lamp") -> make object "lamp":"Lamp" {
                    prop("mode") -> "off"
                }
                has("switch") -> make object "wall":"Switch" {
                    prop("position") -> "down"
                }
            }
        """.trimIndent() + "\n"

        /** A machine that sets a property the lamp lacks and has a state that nothing leads into. */
        val MACHINE = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" {
                        ENTRY {
                            SET("colour", "red")
                        }
                    }
                    STATE "lost" { }
                }
                TRANSITIONS {
                }
            }
        """.trimIndent() + "\n"

        /** A machine without any finding. */
        val CLEAN_MACHINE = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" {
                        ENTRY {
                            SET("mode", "off")
                        }
                    }
                }
                TRANSITIONS {
                }
            }
        """.trimIndent() + "\n"
    }
}
