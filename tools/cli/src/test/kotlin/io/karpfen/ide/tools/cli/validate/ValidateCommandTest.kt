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

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.document.DocumentManager
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import io.karpfen.ide.core.workspace.validation.ProjectValidation
import io.karpfen.ide.core.workspace.validation.ValidationFinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
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
    fun `the workspace is refreshed with every language feature, validated and closed, and its validation becomes the report`() = runTest {
        val workspace = ScriptedWorkspace(root, validationOf(root.resolve("m.kmeta")))
        val command = ValidateCommand(workspace::opened)

        val report = command.run(dir)

        assertEquals(listOf("open", "features", "refresh", "validate", "close"), workspace.calls)
        assertEquals(KstatesFeature.ALL, workspace.features)
        assertTrue(workspace.closed)
        assertFalse(workspace.childJob!!.isActive, "the workspace's coroutines are cancelled")
        assertEquals(listOf(ReportedProblem("m.kmeta", 1, 2, Severity.ERROR, "broken", "code")), report.problems)
        assertEquals(root, report.root)
    }

    @Test
    fun `a failing validation still closes the workspace`() = runTest {
        val workspace = ScriptedWorkspace(root, ProjectValidation(root, emptyList()), failure = IllegalStateException("converter crashed"))
        val command = ValidateCommand(workspace::opened)

        val failure = assertFailsWith<IllegalStateException> { command.run(dir) }

        assertEquals("converter crashed", failure.message)
        assertEquals(listOf("open", "features", "refresh", "validate", "close"), workspace.calls)
        assertFalse(workspace.childJob!!.isActive)
    }

    @Test
    fun `a relative directory is validated as an absolute normalized root`() = runTest {
        val workspace = ScriptedWorkspace(root, ProjectValidation(root, emptyList()))
        var openedRoot: Path? = null
        val command = ValidateCommand({ path, scope -> openedRoot = path; workspace.opened(path, scope) })

        val report = command.run(dir.resolve("sub/.."))

        assertEquals(root, openedRoot)
        assertEquals(ValidationReport(root, emptyList()), report)
    }

    private fun validationOf(path: Path) = ProjectValidation(root, listOf(ValidationFinding(path, 1, 2, Severity.ERROR, "code", "broken")))

    /**
     * A workspace that records the calls and starts a never-ending child coroutine in the factory's scope, which the
     * command must cancel.
     */
    private class ScriptedWorkspace(
        override val root: Path,
        private val result: ProjectValidation,
        private val failure: Exception? = null,
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
        override val languageFeatures: StateFlow<Set<KstatesFeature>> get() = unused()

        /** The language features the command switched on. */
        var features: Set<KstatesFeature>? = null

        override fun setLanguageFeatures(features: Set<KstatesFeature>) {
            record("features")
            this.features = features
        }

        override val problems: StateFlow<WorkspaceProblems> get() = unused()

        override suspend fun refresh() = record("refresh")

        override suspend fun awaitAnalysis(): ProjectAnalysis = unused()

        override suspend fun validate(): ProjectValidation {
            record("validate")
            failure?.let { throw it }
            return result
        }

        override fun close() {
            record("close")
            closed = true
        }

        private fun unused(): Nothing = throw UnsupportedOperationException("validate does not use this")
    }
}
