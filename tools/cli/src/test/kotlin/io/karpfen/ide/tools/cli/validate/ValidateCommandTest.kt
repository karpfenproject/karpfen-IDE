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
package io.karpfen.ide.tools.cli.validate

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.document.DocumentManager
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import io.karpfen.ide.core.workspace.problems.FileProblems
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The validate run's protocol with the workspace, with a scripted fake workspace. */
class ValidateCommandTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()

    @Test
    fun `the workspace is refreshed, analyzed and closed, and its problems become the report`() = runTest {
        val workspace = ScriptedWorkspace(root, problemsOf(root.resolve("m.kmeta")))
        val command = ValidateCommand(workspace::opened)

        val report = command.run(dir)

        assertEquals(listOf("open", "refresh", "awaitAnalysis", "problems", "close"), workspace.calls)
        assertTrue(workspace.closed)
        assertFalse(workspace.childJob!!.isActive, "the workspace's coroutines are cancelled")
        assertEquals(listOf(ReportedProblem("m.kmeta", 1, 2, Severity.ERROR, "broken", "code")), report.problems)
        assertEquals(root, report.root)
    }

    @Test
    fun `a failing analysis still closes the workspace`() = runTest {
        val workspace = ScriptedWorkspace(root, WorkspaceProblems.EMPTY, analysisFailure = IllegalStateException("converter crashed"))
        val command = ValidateCommand(workspace::opened)

        val failure = assertFailsWith<IllegalStateException> { command.run(dir) }

        assertEquals("converter crashed", failure.message)
        assertEquals(listOf("open", "refresh", "awaitAnalysis", "close"), workspace.calls)
        assertFalse(workspace.childJob!!.isActive)
    }

    @Test
    fun `a relative directory is validated as an absolute normalized root`() = runTest {
        val workspace = ScriptedWorkspace(root, WorkspaceProblems.EMPTY)
        var openedRoot: Path? = null
        val command = ValidateCommand({ path, scope -> openedRoot = path; workspace.opened(path, scope) })

        val report = command.run(dir.resolve("sub/.."))

        assertEquals(root, openedRoot)
        assertEquals(ValidationReport(root, emptyList()), report)
    }

    private fun problemsOf(path: Path) = WorkspaceProblems(
        generation = 1,
        files = listOf(
            FileProblems(
                path = path,
                kind = FileKind.KMETA,
                diagnostics = listOf(Diagnostic(TextRange(1, 2), Severity.ERROR, "broken", "code", DiagnosticSource.PARSER)),
                snapshot = TextSnapshot("type", version = 0),
                isOpen = false,
            ),
        ),
    )

    /**
     * A workspace that records the calls and starts a never-ending child coroutine in the factory's scope, which the
     * command must cancel.
     */
    private class ScriptedWorkspace(
        override val root: Path,
        private val result: WorkspaceProblems,
        private val analysisFailure: Exception? = null,
    ) : Workspace {
        /** The calls in order. */
        val calls = mutableListOf<String>()

        /** True once [close] was called. */
        var closed = false

        /** The never-ending child coroutine started on opening. */
        var childJob: Job? = null

        /** Opens this workspace for [path] in [scope], as the factory the command gets. */
        fun opened(path: Path, scope: CoroutineScope): Workspace {
            record("open")
            check(path == root)
            childJob = scope.launch { awaitCancellation() }
            return this
        }

        /** Records [call]. */
        private fun record(call: String) {
            calls += call
        }

        override val languages: LanguageRegistry get() = unused()
        override val files: StateFlow<WorkspaceTree> get() = unused()
        override val scanRevision: StateFlow<Long> get() = unused()
        override val contexts: StateFlow<ContextResolution> get() = unused()
        override val analysis: StateFlow<ProjectAnalysis> get() = unused()
        override val isAnalyzing: StateFlow<Boolean> get() = unused()
        override val documents: DocumentManager get() = unused()

        override val problems: StateFlow<WorkspaceProblems>
            get() = MutableStateFlow(result).also { record("problems") }

        override suspend fun refresh() = record("refresh")

        override suspend fun awaitAnalysis(): ProjectAnalysis {
            record("awaitAnalysis")
            analysisFailure?.let { throw it }
            return ProjectAnalysis.EMPTY
        }

        override fun close() {
            record("close")
            closed = true
        }

        private fun unused(): Nothing = throw UnsupportedOperationException("validate does not use this")
    }
}
