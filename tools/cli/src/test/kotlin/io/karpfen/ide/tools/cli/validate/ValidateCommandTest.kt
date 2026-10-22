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
import io.karpfen.ide.tools.cli.ProcessStreams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The validate run's protocol with the workspace, with a scripted fake workspace. */
class ValidateCommandTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()

    @Test
    fun `the whole workspace run happens silenced and its problems become the report`() = runTest {
        ProcessStreams().use { streams ->
            val processOut = System.out
            val workspace = ScriptedWorkspace(root, problemsOf(root.resolve("m.kmeta")))
            val command = ValidateCommand(StandardOutputGuard(), { path, scope -> workspace.opened(path, scope, System.out) })

            val report = command.run(dir)

            assertEquals(listOf("open", "refresh", "awaitAnalysis", "problems", "close"), workspace.calls)
            workspace.streamsSeen.forEach { assertNotSame(processOut, it, "every workspace call runs with stdout silenced") }
            assertTrue(workspace.closed)
            assertFalse(workspace.childJob!!.isActive, "the workspace's coroutines are cancelled")
            assertEquals(listOf(ReportedProblem("m.kmeta", 1, 2, Severity.ERROR, "broken", "code")), report.problems)
            assertEquals(root, report.root)
            assertEquals("", streams.stdout() + streams.stderr(), "what the workspace printed was discarded")
            assertSame(processOut, System.out)
        }
    }

    @Test
    fun `a failing analysis still closes the workspace and restores the streams`() = runTest {
        val processOut = System.out
        val workspace = ScriptedWorkspace(root, WorkspaceProblems.EMPTY, analysisFailure = IllegalStateException("converter crashed"))
        val command = ValidateCommand(StandardOutputGuard(), { path, scope -> workspace.opened(path, scope, System.out) })

        val failure = assertFailsWith<IllegalStateException> { command.run(dir) }

        assertEquals("converter crashed", failure.message)
        assertEquals(listOf("open", "refresh", "awaitAnalysis", "close"), workspace.calls)
        assertFalse(workspace.childJob!!.isActive)
        assertSame(processOut, System.out)
    }

    @Test
    fun `a relative directory is validated as an absolute normalized root`() = runTest {
        val workspace = ScriptedWorkspace(root, WorkspaceProblems.EMPTY)
        var openedRoot: Path? = null
        val command = ValidateCommand(StandardOutputGuard(), { path, scope -> openedRoot = path; workspace.opened(path, scope, System.out) })

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
     * A workspace that prints on every call (like dsl-tools during the analysis), records the calls, the `System.out`
     * each one saw, and starts a never-ending child coroutine in the factory's scope, which the command must cancel.
     */
    private class ScriptedWorkspace(
        override val root: Path,
        private val result: WorkspaceProblems,
        private val analysisFailure: Exception? = null,
    ) : Workspace {
        /** The calls in order. */
        val calls = mutableListOf<String>()

        /** The `System.out` each call saw, in call order. */
        val streamsSeen = mutableListOf<PrintStream>()

        /** True once [close] was called. */
        var closed = false

        /** The never-ending child coroutine started on opening. */
        var childJob: Job? = null

        /** Opens this workspace for [path] in [scope], printing to [out], as the factory the command gets. */
        fun opened(path: Path, scope: CoroutineScope, out: PrintStream): Workspace {
            record("open", out)
            check(path == root)
            childJob = scope.launch { awaitCancellation() }
            return this
        }

        /** Records [call] and prints to [out] and `System.err` like dsl-tools and ANTLR would. */
        private fun record(call: String, out: PrintStream = System.out) {
            calls += call
            streamsSeen += out
            out.println("dsl-tools chatter during $call")
            System.err.println("ANTLR noise during $call")
        }

        override val languages: LanguageRegistry get() = unused()
        override val files: StateFlow<WorkspaceTree> get() = unused()
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
