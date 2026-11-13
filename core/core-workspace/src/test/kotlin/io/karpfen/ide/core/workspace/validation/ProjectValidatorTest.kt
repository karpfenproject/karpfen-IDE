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
package io.karpfen.ide.core.workspace.validation

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.dsl.DslVerifier
import io.karpfen.ide.core.document.dsl.VerificationInput
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.fixtures.TestWorkspaces
import io.karpfen.ide.core.workspace.problems.FileProblems
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The validation of a whole project through `Workspace.validate`, on small projects on disk. It covers the findings of
 * the analysis together with the checks across files of dsl-tools, the object a descriptor attaches a machine to, the
 * order of the report with its one-based positions, the findings of a loose state machine, the global part of the
 * report with the findings only the validation finds, and that the continuous analysis never runs the checks across
 * files. A recording verifier shows what [ContextVerification] hands over for each context.
 */
class ProjectValidatorTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()

    @Test
    fun `a context without a descriptor is verified without an object, and the report lists errors before warnings`() =
        runTest(timeout = 60.seconds) {
            val machine = writeLamp(root.resolve("lamp"))
            val broken = Files.createDirectories(root.resolve("zz")).resolve("broken.kmeta")
            Files.writeString(broken, "type \"B\" \"b\" {\n")
            val workspace = TestWorkspaces(this).open(root)

            val report = workspace.validate()

            assertEquals(root, report.root)
            assertEquals(
                listOf(
                    Triple(machine, Severity.ERROR, UNKNOWN_PATH),
                    Triple(broken, Severity.ERROR, DiagnosticCodes.SYNTAX_ERROR),
                    Triple(machine, Severity.WARNING, UNREACHABLE),
                ),
                report.findings.map { Triple(it.path, it.severity, it.code) },
            )
            assertEquals(2, report.errorCount)
            assertEquals(1, report.warningCount)
            assertTrue(report.hasErrors)
            assertEquals(report.findings.sortedWith(ValidationFinding.ORDER), report.findings)
            assertAt(machine, report.findings[0], "colour")
            assertAt(machine, report.findings[2], "lost")
            assertEquals(2, report.findings[1].line, "the syntax error at the end of the broken metamodel")
        }

    @Test
    fun `the findings of the verifier form the global part, per file with their ranges in the analyzed text`() =
        runTest(timeout = 60.seconds) {
            val machine = writeLamp(root.resolve("lamp"))
            val broken = Files.createDirectories(root.resolve("zz")).resolve("broken.kmeta")
            Files.writeString(broken, "type \"B\" \"b\" {\n")
            val workspace = TestWorkspaces(this).open(root)

            val report = workspace.validate()
            val analyzed = workspace.analysis.value.files.getValue(machine)

            val global = report.global.single()
            assertEquals(machine, global.path)
            assertEquals(FileKind.KSTATES, global.kind)
            assertFalse(global.isOpen)
            assertSame(analyzed.snapshot, global.snapshot, "the version and the lineage of the analyzed text")
            assertEquals(listOf(UNKNOWN_PATH, UNREACHABLE), global.diagnostics.map { it.code })
            assertEquals(global.diagnostics.sortedWith(Diagnostic.ORDER), global.diagnostics)
            val text = Files.readString(machine)
            assertEquals(listOf(text.indexOf("colour"), text.indexOf("lost")), global.diagnostics.map { it.range.start })
            assertEquals(
                report.findings.filter { it.code.startsWith(PROJECT_CHECKS) },
                global.diagnostics.map { ValidationFinding.of(machine, it, global.positionOf(it)) }.sortedWith(ValidationFinding.ORDER),
                "each global diagnostic is one finding of the flat list",
            )
            assertTrue(report.findings.any { it.path == broken }, "the syntax error is a finding but not global")
        }

    @Test
    fun `a finding the analysis already has, or one the verifier reports twice, is global at most once`() =
        runTest(timeout = 60.seconds) {
            val machine = writeLamp(root, machine = CLEAN_MACHINE)
            val workspace = TestWorkspaces(this).open(root)
            val analysis = workspace.awaitAnalysis()
            val snapshot = analysis.files.getValue(machine).snapshot
            val known = Diagnostic(TextRange(0, 3), Severity.WARNING, "known", "project.known", DiagnosticSource.DSL_TOOLS)
            val fresh = Diagnostic(TextRange(4, 6), Severity.ERROR, "fresh", "project.fresh", DiagnosticSource.DSL_TOOLS)
            val problems = WorkspaceProblems(1, listOf(FileProblems(machine, FileKind.KSTATES, listOf(known), snapshot, isOpen = false)))
            val verifier = object : DslVerifier {
                override fun verify(input: VerificationInput): List<List<Diagnostic>> =
                    input.stateMachines.map { listOf(fresh, known, fresh) }
            }

            val report = DefaultProjectValidator(ContextVerification(verifier)).validate(root, analysis, problems)

            assertEquals(listOf(fresh), report.global.single().diagnostics)
            assertEquals(listOf("project.fresh", "project.known"), report.findings.map { it.code }, "each finding once, errors first")
        }

    @Test
    fun `a descriptor hands its object to the verifier, which reports a machine on an object of another type`() =
        runTest(timeout = 60.seconds) {
            val folder = root.resolve("lamp")
            writeLamp(folder)
            Files.writeString(folder.resolve("lamp.kenv.json"), descriptorAttachingTo("wall"))
            val onWall = TestWorkspaces(this).open(root).validate()

            Files.writeString(folder.resolve("lamp.kenv.json"), descriptorAttachingTo("lamp"))
            val onLamp = TestWorkspaces(this).open(root).validate()

            assertEquals(listOf(Severity.ERROR), onWall.findings.filter { it.code == ATTACHED_OBJECT_TYPE }.map { it.severity })
            assertEquals(emptyList(), onLamp.findings.filter { it.code == ATTACHED_OBJECT_TYPE })
            assertTrue(onLamp.findings.any { it.code == UNKNOWN_PATH }, "${onLamp.findings}")
        }

    @Test
    fun `the continuous analysis never runs the checks across files`() = runTest(timeout = 60.seconds) {
        val machine = writeLamp(root)
        val workspace = TestWorkspaces(this).open(root)

        val analysis = workspace.awaitAnalysis()

        assertFalse(analysis.diagnosticsFor(machine).any { it.code.startsWith(PROJECT_CHECKS) }, "${analysis.diagnosticsFor(machine)}")
        assertFalse(workspace.problems.value.files.flatMap { it.diagnostics }.any { it.code.startsWith(PROJECT_CHECKS) })
        assertTrue(workspace.validate().findings.any { it.code.startsWith(PROJECT_CHECKS) })
    }

    @Test
    fun `the report holds what dsl-tools finds in a loose state machine, read with the features of the project`() =
        runTest(timeout = 60.seconds) {
            writeLamp(root, machine = CLEAN_MACHINE)
            Files.writeString(root.resolve("lamp.kenv.json"), descriptorAttachingTo("lamp"))
            val loose = Files.writeString(root.resolve("spare.kstates"), MACHINE_WITH_BREAKPOINT).toAbsolutePath().normalize()

            val off = TestWorkspaces(this).open(root).validate()
            val on = TestWorkspaces(this).open(root, languageFeatures = TestWorkspaces.ALL_FEATURES).validate()

            assertEquals(listOf(loose to FEATURE_OFF), off.findings.map { it.path to it.code })
            assertEquals(ProjectValidation(root, emptyList()), on)
        }

    @Test
    fun `a clean project has an empty report`() = runTest(timeout = 60.seconds) {
        writeLamp(root, machine = CLEAN_MACHINE)

        val report = TestWorkspaces(this).open(root).validate()

        assertEquals(ProjectValidation(root, emptyList()), report)
        assertFalse(report.hasErrors)
    }

    @Test
    fun `each context hands its own reads and the attached objects of its descriptor to the verifier`() =
        runTest(timeout = 60.seconds) {
            val withDescriptor = root.resolve("a")
            writeLamp(withDescriptor)
            Files.writeString(withDescriptor.resolve("lamp.kenv.json"), descriptorAttachingTo("wall"))
            writeLamp(root.resolve("b"))
            val metamodelOnly = Files.createDirectories(root.resolve("c"))
            Files.writeString(metamodelOnly.resolve("only.kmeta"), METAMODEL)
            val analysis = TestWorkspaces(this).open(root).awaitAnalysis()
            val recording = RecordingVerifier()

            val findings = analysis.contexts.flatMap { ContextVerification(recording).findings(it) }

            assertEquals(listOf(listOf("wall"), listOf(null)), recording.inputs.map { input -> input.stateMachines.map { it.attachedObjectId } })
            assertTrue(recording.inputs.all { it.model != null && it.events == null }, "each context names one model and no events")
            assertEquals(
                listOf(withDescriptor.resolve("lamp.kstates"), root.resolve("b/lamp.kstates")),
                findings.map { it.first },
                "a context without state machines is not verified",
            )
        }

    /**
     * Asserts that [finding] is in [file] at the one-based line and column where [needle] first appears. The checks
     * place a name at its first letter, inside its quotes.
     */
    private fun assertAt(file: Path, finding: ValidationFinding, needle: String) {
        val lines = Files.readAllLines(file)
        val line = lines.indexOfFirst { needle in it }
        assertEquals(line + 1, finding.line, "$finding")
        assertEquals(lines[line].indexOf(needle) + 1, finding.column, "$finding")
    }

    /** Writes the lamp project into [folder] with [machine] as its state machine and returns the machine's path. */
    private fun writeLamp(folder: Path, machine: String = MACHINE): Path {
        Files.createDirectories(folder)
        Files.writeString(folder.resolve("lamp.kmeta"), METAMODEL)
        Files.writeString(folder.resolve("lamp.kmodel"), MODEL)
        return Files.writeString(folder.resolve("lamp.kstates"), machine).toAbsolutePath().normalize()
    }

    /** A descriptor of the lamp project that attaches its machine to the object [key]. */
    private fun descriptorAttachingTo(key: String): String = """
        {
          "metamodel": "lamp.kmeta",
          "model": "lamp.kmodel",
          "stateMachines": [
            { "file": "lamp.kstates", "attachedTo": "$key" }
          ]
        }
    """.trimIndent() + "\n"

    /** A verifier that records each input and finds nothing. */
    private class RecordingVerifier : DslVerifier {
        val inputs = mutableListOf<VerificationInput>()

        override fun verify(input: VerificationInput): List<List<Diagnostic>> {
            inputs += input
            return input.stateMachines.map { emptyList() }
        }
    }

    private companion object {
        /** The prefix of the codes of the checks across files. */
        const val PROJECT_CHECKS = "project."

        /** The code of a path that the type of the machine does not have. */
        const val UNKNOWN_PATH = "project.unknown-path"

        /** The code of a state that nothing leads into. */
        const val UNREACHABLE = "project.unreachable-state"

        /** The code of a construct of a language feature that is off. */
        const val FEATURE_OFF = "kstates.feature-off"

        /** The code of a machine attached to an object of another type than it names. */
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

        /** A machine that halts at a breakpoint, which needs the language feature of breakpoints. */
        val MACHINE_WITH_BREAKPOINT = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" {
                        BREAKPOINT { ENTRY }
                    }
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
