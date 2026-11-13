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
package io.karpfen.ide.core.workspace

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.text.TextSnapshot
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
 * Reading a text in the place of a file with [Workspace.readInPlace]: the text is read in the role of the file in its
 * context, with the language features of the project, and nothing of the workspace changes.
 */
class WorkspaceReadInPlaceTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()

    private val metamodel = "type \"Lamp\" \"l\" {\n    prop(\"brightness\", \"number\")\n}\n"
    private val machine = """
        STATEMACHINE ATTACHED TO "Lamp" {
            STATES {
                INITIAL STATE "off" {
                    ENTRY { SET("brightness", "0") }
                }
            }
            TRANSITIONS { }
        }
    """.trimIndent() + "\n"
    private val model = "make object \"lamp\":\"Lamp\" {\n    prop(\"brightness\") -> \"0\"\n}\n"

    private fun write(): Triple<Path, Path, Path> {
        Files.writeString(root.resolve("m.kmeta"), metamodel)
        Files.writeString(root.resolve("m.kmodel"), model)
        Files.writeString(root.resolve("m.kstates"), machine)
        return Triple(root.resolve("m.kmeta"), root.resolve("m.kmodel"), root.resolve("m.kstates"))
    }

    @Test
    fun `a state machine is read with the features of the project and the file stays as it is`() = runTest(timeout = 60.seconds) {
        val (_, _, states) = write()
        val workspace = TestWorkspaces(this).open(root, languageFeatures = TestWorkspaces.ALL_FEATURES)
        val before = workspace.awaitAnalysis()

        val text = machine.replace("SET(\"brightness\", \"0\")", "SET(\"brightness\", MACRO(\"nope\")) SET(\"brightness\", EVAL { x = 1 })")
        val found = workspace.readInPlace(states, TextSnapshot(text, 7))

        assertEquals(listOf("kstates.unknown-macro", "kstates.eval-without-return"), found.map { it.code })
        assertTrue(found.all { it.source == DiagnosticSource.DSL_TOOLS })
        assertEquals("nope", marked(text, found.first()))
        assertEquals(before, workspace.analysis.value, "the analysis stays as it is")
        assertEquals(emptyList(), workspace.analysis.value.diagnosticsFor(states))
    }

    @Test
    fun `a construct of a feature that is off is reported as the analysis reports it`() = runTest(timeout = 60.seconds) {
        val (_, _, states) = write()
        val workspace = TestWorkspaces(this).open(root)
        workspace.awaitAnalysis()

        val text = machine.replace("INITIAL STATE", "INITIAL SHALLOW HISTORY STATE")
        val found = workspace.readInPlace(states, TextSnapshot(text, 1))

        val off = found.single { it.code == "kstates.feature-off" }
        assertEquals("SHALLOW", marked(text, off))
    }

    @Test
    fun `a model is read against the metamodel of its context`() = runTest(timeout = 60.seconds) {
        val (_, kmodel, _) = write()
        val workspace = TestWorkspaces(this).open(root)
        workspace.awaitAnalysis()

        val text = model.replace("prop(\"brightness\")", "prop(\"colour\")")
        val found = workspace.readInPlace(kmodel, TextSnapshot(text, 1))

        assertEquals(listOf("kmodel.unknown-property"), found.map { it.code })
    }

    @Test
    fun `a text with syntax errors is not read`() = runTest(timeout = 60.seconds) {
        val (_, _, states) = write()
        val workspace = TestWorkspaces(this).open(root)
        workspace.awaitAnalysis()

        val text = machine.replace("SET(\"brightness\", \"0\")", "SET(\"brightness\", EVAL { x = 1 }")

        assertEquals(emptyList(), workspace.readInPlace(states, TextSnapshot(text, 1)))
    }

    /** The text of [text] that [diagnostic] marks. */
    private fun marked(text: String, diagnostic: Diagnostic): String = text.substring(diagnostic.range.start, diagnostic.range.end)
}
