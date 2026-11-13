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
package io.karpfen.ide.ui.workbench.problems

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.problems.FileProblems
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import io.karpfen.ide.core.workspace.validation.ProjectValidation
import io.karpfen.ide.ui.workbench.testing.FakeWorkspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests that [ProblemsState] maps `Workspace.problems` into the Local part and the global findings of the last report
 * into the Global part, with groups and items with one-based positions, that it filters both parts, and that it counts
 * both parts together before filtering. The Global part is absent before the first validation, empty after one without
 * global findings, kept while the local problems change, and replaced by the next report.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProblemsStateTest {

    private val root: Path = Path.of("/ws").toAbsolutePath()
    private val machine = root.resolve("sub/robot.kstates")
    private val descriptor = root.resolve("env.kenv.json")
    private val text = TextSnapshot("STATE \"a\" {\n  oops\n}\n", 4)
    private val analyzed = TextSnapshot("STATE \"a\" {\n  lost\n}\n", 2)
    private val reports = MutableStateFlow<ProjectValidation?>(null)

    private fun diagnostic(start: Int, severity: Severity, source: DiagnosticSource = DiagnosticSource.PARSER) =
        Diagnostic(TextRange(start, start + 1), severity, "m$start", "code", source)

    private val problems = WorkspaceProblems(
        generation = 3,
        files = listOf(
            FileProblems(descriptor, FileKind.ENV_DESCRIPTOR, listOf(diagnostic(0, Severity.WARNING)), snapshot = null, isOpen = false),
            FileProblems(
                machine, FileKind.KSTATES,
                listOf(diagnostic(2, Severity.ERROR), diagnostic(14, Severity.WARNING), diagnostic(15, Severity.INFO)),
                snapshot = text, isOpen = true,
            ),
        ),
    )

    /** A report whose global part has an error and a warning of the verifier in [machine], in the [analyzed] text. */
    private val report = ProjectValidation(
        root,
        findings = emptyList(),
        global = listOf(
            FileProblems(
                machine, FileKind.KSTATES,
                listOf(diagnostic(15, Severity.ERROR, DiagnosticSource.DSL_TOOLS), diagnostic(16, Severity.WARNING, DiagnosticSource.DSL_TOOLS)),
                snapshot = analyzed, isOpen = false,
            ),
        ),
    )

    @Test
    fun `problems are grouped by file with one-based positions, versions and relative paths`() = runTest {
        val scope = scopeOf(this)
        val state = ProblemsState(scope, reports)
        val workspace = FakeWorkspace(root)
        state.bindWorkspace(workspace)
        workspace.problems.value = problems
        advanceUntilIdle()

        val ui = state.state.value
        assertTrue(ui.hasFolder)
        assertEquals(1, ui.errorCount)
        assertEquals(2, ui.warningCount)
        assertEquals(listOf("env.kenv.json", "sub/robot.kstates"), ui.local.map { it.displayPath })
        val items = ui.local[1].items
        assertEquals(listOf(1 to 3, 2 to 3, 2 to 4), items.map { it.line to it.column })
        assertEquals(4L, items.first().version)
        assertTrue(items.first().lineage === text.lineage)
        assertEquals(TextRange(2, 3), items.first().location.range)
        val unknown = ui.local[0].items.single()
        assertEquals(1 to 1, unknown.line to unknown.column, "no snapshot: the origin")
        assertEquals(null, unknown.version)
        assertNull(ui.global, "not validated yet")
        scope.cancel()
    }

    @Test
    fun `the filter hides severities but not the counts, and empty groups disappear`() = runTest {
        val scope = scopeOf(this)
        val state = ProblemsState(scope, reports)
        val workspace = FakeWorkspace(root)
        workspace.problems.value = problems
        state.bindWorkspace(workspace)

        state.setFilter(ProblemFilter(showWarnings = false, showInfos = false))
        val ui = state.state.value
        assertEquals(listOf(machine), ui.local.map { it.path })
        assertEquals(listOf(Severity.ERROR), ui.local.single().items.map { it.diagnostic.severity })
        assertEquals(2, ui.warningCount)
        assertEquals(ProblemFilter(showWarnings = false, showInfos = false), ui.filter)

        state.bindWorkspace(null)
        assertEquals(ProblemsUiState(filter = ui.filter), state.state.value, "unbinding keeps the filter")
        assertFalse(state.state.value.hasFolder)
        scope.cancel()
    }

    @Test
    fun `the global findings of the last report form the Global part, filtered and counted with the local ones`() = runTest {
        val scope = scopeOf(this)
        val state = ProblemsState(scope, reports)
        val workspace = FakeWorkspace(root)
        workspace.problems.value = problems
        state.bindWorkspace(workspace)
        reports.value = report
        advanceUntilIdle()

        val ui = state.state.value
        assertEquals(2, ui.errorCount, "one local and one global error")
        assertEquals(3, ui.warningCount, "two local and one global warning")
        val global = ui.global!!.single()
        assertEquals("sub/robot.kstates", global.displayPath)
        assertEquals(listOf(2 to 4, 2 to 5), global.items.map { it.line to it.column })
        assertEquals(2L, global.items.first().version, "the version of the analyzed text")
        assertTrue(global.items.first().lineage === analyzed.lineage)
        assertEquals(machine, global.items.first().location.path)
        assertEquals(TextRange(15, 16), global.items.first().location.range)

        state.setFilter(ProblemFilter(showErrors = false))
        assertEquals(listOf(Severity.WARNING), state.state.value.global!!.single().items.map { it.diagnostic.severity })
        state.setFilter(ProblemFilter(showErrors = false, showWarnings = false))
        assertEquals(emptyList(), state.state.value.global, "a filtered-out group disappears")
        assertEquals(2, state.state.value.errorCount, "the counts are taken before filtering")
        scope.cancel()
    }

    @Test
    fun `the Global part is kept while the local problems change and replaced by the next report`() = runTest {
        val scope = scopeOf(this)
        val state = ProblemsState(scope, reports)
        val workspace = FakeWorkspace(root)
        state.bindWorkspace(workspace)
        reports.value = report
        advanceUntilIdle()
        assertEquals(emptyList(), state.state.value.local)
        assertEquals(2, state.state.value.global!!.single().items.size)

        workspace.problems.value = problems
        advanceUntilIdle()
        assertEquals(2, state.state.value.local.size, "an edit changes the Local part")
        assertEquals(2, state.state.value.global!!.single().items.size, "and keeps the Global part")

        reports.value = ProjectValidation(root, emptyList())
        advanceUntilIdle()
        assertEquals(emptyList(), state.state.value.global, "a validation without global findings")
        assertEquals(1, state.state.value.errorCount)

        reports.value = null
        advanceUntilIdle()
        assertNull(state.state.value.global, "the report was cleared")
        scope.cancel()
    }

    @Test
    fun `the view is empty only without problems in both parts`() {
        assertTrue(ProblemsUiState(hasFolder = true, global = emptyList()).isEmpty)
        val group = ProblemGroup(machine, "sub/robot.kstates", listOf(ProblemItem(machine, diagnostic(0, Severity.INFO), 1, 1)))
        assertFalse(ProblemsUiState(hasFolder = true, global = listOf(group)).isEmpty)
        assertFalse(ProblemsUiState(hasFolder = true, local = listOf(group)).isEmpty)
    }

    @Test
    fun `hints count as infos`() {
        val filter = ProblemFilter(showInfos = false)
        assertTrue(!ProblemsMapping.shows(filter, Severity.HINT))
        assertTrue(ProblemsMapping.shows(filter, Severity.ERROR))
    }

    /** A scope on the scheduler of [test] that each test cancels at its end. */
    private fun scopeOf(test: TestScope): CoroutineScope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + SupervisorJob())
}
