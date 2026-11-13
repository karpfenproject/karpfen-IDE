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
package io.karpfen.ide.ui.workbench.tabs

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.services.defaultLanguageServices
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.fixtures.TestWorkspaces
import io.karpfen.ide.core.workspace.fixtures.openDocument
import io.karpfen.ide.core.workspace.fixtures.settle
import io.karpfen.ide.notation.diagram.edit.DiagramCell
import io.karpfen.ide.notation.diagram.edit.StatechartCells
import io.karpfen.ide.notation.diagram.layout.StatechartLayouter
import io.karpfen.ide.notation.diagram.layout.TextMeasurer
import io.karpfen.ide.notation.diagram.statechart.StatechartProjection
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * An open code cell of a statechart with the real parser, the real reader of dsl-tools and the real language services
 * of a workspace: what the cell shows is what the same code shows in the text of its file, and nothing that stands
 * elsewhere in the file.
 */
class CodeCellSessionTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()

    private val machine = """
        STATEMACHINE ATTACHED TO "Lamp" {
            STATES {
                INITIAL STATE "off" {
                    ENTRY { SET("brightness", "0") }
                }
                STATE "on" {
                    ENTRY { SET("brightness", EVAL { y = 2 }) }
                }
            }
            TRANSITIONS { }
            MACROS {
                MACRO "half" { TAKES("v", "number") RETURNS("number") DEFINITION { EVAL { return v / 2 } } }
            }
        }
    """.trimIndent() + "\n"

    @Test
    fun `a syntax error typed into a cell is reported at its place in the cell`() = runTest(timeout = 60.seconds) {
        val cell = open()
        val code = "SET(\"brightness\", \"1\")\nSET(\"brightness\" \"2\")"

        val shown = typed(cell, code)

        val error = shown.single { it.source == DiagnosticSource.PARSER }
        assertTrue(error.range.start >= code.indexOf('\n'), "the error ${error.range} lies on the second line")
        assertTrue(error.range.end <= code.length)
    }

    @Test
    fun `a syntax error that the parser reports behind the cell stands at the end of the cell`() = runTest(timeout = 60.seconds) {
        val cell = open()
        val code = "SET(\"brightness\", \"1\""

        val shown = typed(cell, code)

        assertEquals(code.length, shown.first { it.source == DiagnosticSource.PARSER }.range.end)
    }

    @Test
    fun `what the reader finds in the code of a cell is reported in the cell`() = runTest(timeout = 60.seconds) {
        val cell = open()
        val code = "SET(\"brightness\", EVAL { x = 1 })\nSET(\"brightness\", MACRO(\"nope\"))"

        val shown = typed(cell, code)

        assertEquals(listOf("kstates.eval-without-return", "kstates.unknown-macro"), shown.map { it.code })
        assertTrue(shown.all { it.source == DiagnosticSource.DSL_TOOLS })
        assertEquals("nope", code.substring(shown[1].range.start, shown[1].range.end))
        assertTrue(code.substring(shown[0].range.start, shown[0].range.end).isNotBlank())
    }

    @Test
    fun `a finding elsewhere in the file is not the cell's`() = runTest(timeout = 60.seconds) {
        val cell = open()
        assertTrue(cell.workspace.analysis.value.diagnosticsFor(cell.file).any { it.code == "kstates.eval-without-return" })

        val shown = typed(cell, "SET(\"brightness\", \"1\")")

        assertEquals(emptyList(), shown)
    }

    @Test
    fun `completion in a cell knows the file around it`() = runTest(timeout = 60.seconds) {
        val cell = open()
        val code = "SET(\"brightness\", MACRO(\"h"
        typed(cell, code)

        val session = cell.opened.session
        val items = cell.opened.services.complete(LanguageDocument.of(session), code.length)

        val half = assertNotNull(items.firstOrNull { it.label == "half" }, "the macro of the file is offered: ${items.map { it.label }}")
        assertEquals(code.length - 1, half.replaceRange.start)
    }

    /** An open cell of the entry actions of the state "off", with the workspace and the file it belongs to. */
    private class OpenCell(val workspace: Workspace, val file: Path, val opened: CodeCellSession)

    /** Opens the folder with the machine, every language feature on, and the cell of the entry actions of "off". */
    private suspend fun TestScope.open(): OpenCell {
        val file = root.resolve("m.kstates")
        Files.writeString(root.resolve("m.kmeta"), "type \"Lamp\" \"l\" {\n    prop(\"brightness\", \"number\")\n}\n")
        Files.writeString(file, machine)
        val workspaces = TestWorkspaces(this)
        val workspace = workspaces.open(root, languageFeatures = TestWorkspaces.ALL_FEATURES)
        val session = workspace.openDocument(file).session
        workspace.awaitAnalysis()
        val config = DocumentSessionConfig(tokenizeDispatcher = workspaces.dispatcher, parseDispatcher = workspaces.dispatcher)
        val services = defaultLanguageServices(workspace.analysis, files = workspace.files)
        val opened = CodeCellSession(session, cellOf(machine), services, workspace, backgroundScope, config)
        settle()
        return OpenCell(workspace, file, opened)
    }

    /** The cell of the entry actions of the state "off" of [source]. */
    private fun cellOf(source: String): DiagramCell {
        val outline = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES).parser.parse(TextSnapshot(source, 0)).outline
        val diagram = assertNotNull(StatechartProjection.of(source, outline))
        val layout = StatechartLayouter().layout(diagram, TextMeasurer.estimating())
        return StatechartCells.of(source, diagram, layout).single { it.key == "off.entry" }
    }

    /** Replaces the code of [cell] with [code], lets parsing and checks run and gives what the cell shows. */
    private fun TestScope.typed(cell: OpenCell, code: String): List<Diagnostic> {
        cell.opened.session.text.replaceAll(code, EditOrigin.Typing)
        settle()
        return cell.opened.session.diagnostics.value.diagnostics
    }
}
