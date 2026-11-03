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

import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.ClassType
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.dsl.ReadResult
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.analysis.ProjectAnalyzer
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.core.workspace.fixtures.TestWorkspaces
import io.karpfen.ide.core.workspace.fixtures.openDocument
import io.karpfen.ide.core.workspace.fixtures.settle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * An environment descriptor open in an editor counts with its unsaved text: its findings appear in its session at the
 * edited ranges and in the problems, and the contexts and the analysis follow its edits, also an edit of a value that
 * names no file, which the context carries in its typed descriptor and which the analysis follows without reading a
 * file again; syntax errors are reported once; closing it without saving falls back to the disk.
 */
class WorkspaceDescriptorEditingTest {

    /** The real readers of dsl-tools, counting their reads. */
    private class CountingReader(private val delegate: DslReader = DslReader()) : DslReader {
        /** The number of reads so far. */
        var reads: Int = 0
            private set

        override fun kmeta(source: TextSnapshot, baseTypes: List<ClassType>): ReadResult<Metamodel> =
            delegate.kmeta(source, baseTypes).also { reads++ }

        override fun kmodel(source: TextSnapshot, metamodel: Metamodel): ReadResult<Model> =
            delegate.kmodel(source, metamodel).also { reads++ }

        override fun kstates(source: TextSnapshot): ReadResult<StateMachine> = delegate.kstates(source).also { reads++ }
    }

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()
    private val ctx: Path get() = root.resolve("ctx")

    /** The diagnostics of the open [document] with the text they cover. */
    private fun OpenDocument.findings(): List<Pair<String, String>> {
        val text = session.text.snapshot.value.text
        return session.diagnostics.value.diagnostics.map { it.code to text.substring(it.range.start, it.range.end) }
    }

    /** Replaces the first [old] of the document's text by [new], as the user would type it. */
    private fun OpenDocument.replace(old: String, new: String) {
        val start = session.text.snapshot.value.text.indexOf(old)
        require(start >= 0) { "'$old' not in the text" }
        session.text.apply(listOf(TextEdit.replace(TextRange.ofLength(start, old.length), new)), EditOrigin.Typing)
    }

    @Test
    fun `unsaved edits of an open descriptor change its findings, the contexts and the analysis`() = runTest(timeout = 60.seconds) {
        val descriptor = DescriptorExamples.copyExplicitContext(ctx).toAbsolutePath().normalize()
        val workspace = TestWorkspaces(this).open(root)
        workspace.awaitAnalysis()
        val document = workspace.openDocument(descriptor)
        settle()
        assertEquals(emptyList(), document.findings())
        val model = ctx.resolve("cleaning_robot.kmodel")
        assertTrue(model in workspace.contexts.value.contexts.single(), "the model is in the descriptor's context")

        document.replace("cleaning_robot.kmodel", "missing.kmodel")
        document.replace("\"tickDelayMs\"", "\"tickDelay\"")
        settle()

        assertEquals(
            listOf(WorkspaceDiagnosticCodes.DESCRIPTOR_MISSING_FILE to "missing.kmodel", WorkspaceDiagnosticCodes.DESCRIPTOR_UNKNOWN_KEY to "tickDelay"),
            document.findings(),
            "found in the unsaved text, at its ranges",
        )
        val problems = assertNotNull(workspace.problems.value.of(descriptor))
        assertTrue(problems.isOpen)
        assertEquals(document.session.diagnostics.value.diagnostics, problems.diagnostics)
        assertFalse(model in workspace.contexts.value.contexts.single(), "the context follows the edit")
        assertTrue(model in workspace.analysis.value.looseFiles, "and so does the analysis: the model is loose now")
        assertTrue(Files.readString(descriptor).contains("cleaning_robot.kmodel"), "nothing was saved")
    }

    @Test
    fun `editing only the tick delay yields a new resolution and an analysis that reads no file again`() = runTest(timeout = 60.seconds) {
        val descriptor = DescriptorExamples.copyExplicitContext(ctx).toAbsolutePath().normalize()
        val reader = CountingReader()
        val workspace = TestWorkspaces(this).open(root, analyzer = ProjectAnalyzer(defaultLanguageRegistry(), reader))
        workspace.awaitAnalysis()
        val document = workspace.openDocument(descriptor)
        settle()
        val before = workspace.contexts.value.contexts.single()
        val analysisBefore = workspace.analysis.value
        val readsBefore = reader.reads
        assertEquals(50L, before.environment?.tickDelayMs)

        document.replace("\"tickDelayMs\": 50", "\"tickDelayMs\": 75")
        settle()

        val after = workspace.contexts.value.contexts.single()
        assertEquals(75L, after.environment?.tickDelayMs, "the context carries the edited descriptor")
        assertEquals(before.copy(environment = after.environment), after, "nothing but the descriptor changed")
        val analysis = workspace.analysis.value
        assertTrue(analysis.generation > analysisBefore.generation, "the new resolution was analyzed")
        assertEquals(after, analysis.contexts.single().context)
        assertEquals(readsBefore, reader.reads, "no file was read again: the analyzer remembers its reads by text")
        assertEquals(analysisBefore.contexts.single().models, analysis.contexts.single().models)
    }

    @Test
    fun `a syntax error of an open descriptor is reported once and closing without saving falls back to the disk`() = runTest(timeout = 60.seconds) {
        val descriptor = DescriptorExamples.copyExplicitContext(ctx).toAbsolutePath().normalize()
        val workspace = TestWorkspaces(this).open(root)
        workspace.awaitAnalysis()
        val document = workspace.openDocument(descriptor)
        settle()

        document.replace("\"model\":", "\"model\"")
        settle()

        val syntax = document.session.diagnostics.value.diagnostics
        assertEquals(listOf(DiagnosticSource.PARSER), syntax.map { it.source }, "$syntax")
        assertEquals(DiagnosticCodes.SYNTAX_ERROR, syntax.single().code)
        assertEquals(syntax, workspace.problems.value.of(descriptor)?.diagnostics)
        val project = workspace.analysis.value.diagnosticsFor(descriptor)
        assertEquals(emptyList(), project, "the session owns the syntax errors, the project diagnostics never hold them")
        assertEquals(emptyList(), workspace.contexts.value.contexts.filter { it.descriptor == descriptor }, "a broken descriptor defines no context")

        workspace.documents.close(descriptor)
        settle()

        assertEquals(null, workspace.problems.value.of(descriptor), "the disk text has no findings")
        assertEquals(descriptor, workspace.contexts.value.contexts.single { it.directory == ctx }.descriptor)
    }
}
