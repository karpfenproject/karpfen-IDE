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

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.core.workspace.fixtures.TestWorkspaces
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The validation of compositions of fragments on small lamp projects on disk: a folder takes its fragments in, every
 * fragment of a context reaches the verifier of dsl-tools, and the findings of a composition land in the file whose
 * text they concern, the state machine or the fragment. It also covers the workspace's own findings about the names of
 * fragment files and the form of a fragment entry in a descriptor.
 */
class PagesValidationTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()

    @Test
    fun `a composition that flattens has no findings, with the fragments taken in by the folder`() = runTest(timeout = 60.seconds) {
        writeLamp(host = host("bulb test"))
        write("bulb test.frag.kstates", fragment("bulb test", "INITIAL STATE \"lit\" { ENTRY { SET(\"mode\", \"on\") } }"))
        val workspace = TestWorkspaces(this).open(root)

        val report = workspace.validate()

        assertEquals(emptyList(), report.findings)
        val context = workspace.analysis.value.contexts.single()
        assertEquals(listOf("bulb test.frag.kstates", "lamp.kstates"), context.context.stateMachines.map { it.file.fileName.toString() })
    }

    @Test
    fun `a finding inside the content of a fragment lands in the fragment, not in the state machine that uses it`() =
        runTest(timeout = 60.seconds) {
            val machine = writeLamp(host = host("bulb test"))
            val used = write("bulb test.frag.kstates", fragment("bulb test", "INITIAL STATE \"lit\" { ENTRY { SET(\"colour\", \"red\") } }"))

            val report = TestWorkspaces(this).open(root).validate()

            assertEquals(listOf(used to UNKNOWN_PATH), projectFindings(report))
            assertAt(used, report.findings.single(), "colour")
            assertTrue(report.global.none { it.path == machine })
        }

    @Test
    fun `a container state that uses an unknown fragment is an error at the name after USE`() = runTest(timeout = 60.seconds) {
        val machine = writeLamp(host = host("missing"))

        val report = TestWorkspaces(this).open(root).validate()

        assertEquals(listOf(machine to UNKNOWN_FRAGMENT), projectFindings(report))
        assertEquals(Severity.ERROR, report.findings.single().severity)
        assertAt(machine, report.findings.single(), "missing")
    }

    @Test
    fun `fragments that use each other are a cycle, reported in a fragment`() = runTest(timeout = 60.seconds) {
        writeLamp(host = host("first"))
        val first = write("first.frag.kstates", fragment("first", "INITIAL CONTAINER STATE \"inner\" USE \"second\" { }"))
        val second = write("second.frag.kstates", fragment("second", "INITIAL CONTAINER STATE \"inner\" USE \"first\" { }"))

        val report = TestWorkspaces(this).open(root).validate()

        val cycles = report.findings.filter { it.code == FRAGMENT_CYCLE }
        assertTrue(cycles.isNotEmpty(), "${report.findings}")
        assertTrue(cycles.all { it.path == first || it.path == second }, "$cycles")
        assertTrue(report.global.any { file -> file.diagnostics.any { it.code == FRAGMENT_CYCLE } })
    }

    @Test
    fun `a descriptor lists a fragment without attachedTo, and only the fragments it lists are in its context`() =
        runTest(timeout = 60.seconds) {
            val machine = writeLamp(host = host("bulb test"))
            write("bulb test.frag.kstates", fragment("bulb test", "INITIAL STATE \"lit\" { }"))
            val listed = descriptor("""{ "file": "lamp.kstates", "attachedTo": "lamp" }, { "file": "bulb test.frag.kstates" }""")
            val workspace = TestWorkspaces(this).open(root)

            assertEquals(emptyList(), workspace.validate().findings.filter { it.severity == Severity.ERROR })

            Files.writeString(listed, Files.readString(listed).replace(""", { "file": "bulb test.frag.kstates" }""", ""))
            val unlisted = TestWorkspaces(this).open(root).validate()
            assertEquals(listOf(machine to UNKNOWN_FRAGMENT), projectFindings(unlisted))
        }

    @Test
    fun `a fragment entry with attachedTo is a form error at the entry`() = runTest(timeout = 60.seconds) {
        writeLamp(host = host("bulb test"))
        write("bulb test.frag.kstates", fragment("bulb test", "INITIAL STATE \"lit\" { }"))
        val descriptor = descriptor(
            """{ "file": "lamp.kstates", "attachedTo": "lamp" }, { "file": "bulb test.frag.kstates", "attachedTo": "lamp" }""",
        )

        val analysis = TestWorkspaces(this).open(root).awaitAnalysis()

        val form = analysis.diagnosticsFor(descriptor).filter { it.code == WorkspaceDiagnosticCodes.DESCRIPTOR_STATE_MACHINE_FORM }
        assertEquals(listOf(Severity.ERROR), form.map { it.severity })
        assertTrue("fragment" in form.single().message, form.single().message)
    }

    @Test
    fun `a fragment in a file of another name and a frag kstates file without a fragment are warnings`() =
        runTest(timeout = 60.seconds) {
            writeLamp(host = host("bulb test"))
            val misnamed = write("bulb.frag.kstates", fragment("bulb test", "INITIAL STATE \"lit\" { }"))
            val plain = write("other.frag.kstates", Files.readString(root.resolve("lamp.kstates")).replace("USE \"bulb test\"", "").replace("CONTAINER ", ""))
            val fitting = write("spare.frag.kstates", fragment("spare", "INITIAL STATE \"lit\" { }"))

            val analysis = TestWorkspaces(this).open(root).awaitAnalysis()

            fun names(path: Path) = analysis.diagnosticsFor(path).filter { it.code == WorkspaceDiagnosticCodes.FRAGMENT_FILE_NAME }
            assertEquals(listOf(Severity.WARNING to "bulb test"), names(misnamed).map { it.severity to Files.readString(misnamed).substring(it.range.start, it.range.end) })
            assertEquals(listOf(Severity.WARNING), names(plain).map { it.severity })
            assertEquals(emptyList(), names(fitting))
            assertEquals(emptyList(), names(root.resolve("lamp.kstates")))
        }

    /** The findings of the checks across files of [report], each as its file and its code. */
    private fun projectFindings(report: ProjectValidation): List<Pair<Path, String>> =
        report.findings.filter { it.code.startsWith("project.") }.map { it.path to it.code }

    /** Asserts that [finding] is in [file] at the one-based line and column where [needle] first appears. */
    private fun assertAt(file: Path, finding: ValidationFinding, needle: String) {
        val lines = Files.readAllLines(file)
        val line = lines.indexOfFirst { needle in it }
        assertEquals(line + 1, finding.line, "$finding")
        assertEquals(lines[line].indexOf(needle) + 1, finding.column, "$finding")
    }

    /** Writes the metamodel, the model and [host] as `lamp.kstates` into the root and returns the machine's path. */
    private fun writeLamp(host: String): Path {
        write("lamp.kmeta", METAMODEL)
        write("lamp.kmodel", MODEL)
        return write("lamp.kstates", host)
    }

    /** Writes [text] to the file [name] in the root and returns its path. */
    private fun write(name: String, text: String): Path = Files.writeString(root.resolve(name), text).toAbsolutePath().normalize()

    /** Writes a descriptor of the lamp project with the state machine [entries] and returns its path. */
    private fun descriptor(entries: String): Path = write(
        "lamp.kenv.json",
        """{ "metamodel": "lamp.kmeta", "model": "lamp.kmodel", "stateMachines": [ $entries ] }""" + "\n",
    )

    /** A state machine of the lamp whose container state "self test" uses the fragment [fragment]. */
    private fun host(fragment: String): String = """
        STATEMACHINE ATTACHED TO "Lamp" {
            STATES {
                INITIAL STATE "off" { }
                CONTAINER STATE "self test" USE "$fragment" { }
            }
            TRANSITIONS {
                TRANSITION "off" -> "self test" { }
                TRANSITION "self test" -> "off" { CONDITION { DONE } }
            }
        }
    """.trimIndent() + "\n"

    /** A fragment named [name] whose initial state is [initial], a state named "inner" or "lit", and a final state. */
    private fun fragment(name: String, initial: String): String {
        val first = Regex("STATE \"([^\"]*)\"").find(initial)!!.groupValues[1]
        return """
            |STATEMACHINE "$name" FRAGMENT {
            |    STATES {
            |        $initial
            |        FINAL STATE "done" { }
            |    }
            |    TRANSITIONS {
            |        TRANSITION "$first" -> "done" { }
            |    }
            |}
            |""".trimMargin()
    }

    private companion object {
        const val UNKNOWN_PATH = "project.unknown-path"
        const val UNKNOWN_FRAGMENT = "project.unknown-fragment"
        const val FRAGMENT_CYCLE = "project.fragment-cycle"

        val METAMODEL = """
            type "Lamp" "l" {
                prop("mode", "string")
            }
            type "Room" "r" {
                has("lamp", "Lamp")
            }
        """.trimIndent() + "\n"

        val MODEL = """
            make object "room":"Room" {
                has("lamp") -> make object "lamp":"Lamp" {
                    prop("mode") -> "off"
                }
            }
        """.trimIndent() + "\n"
    }
}
