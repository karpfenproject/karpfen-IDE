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
import io.karpfen.ide.ui.workbench.testing.FakeWorkspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that [ProblemsState] maps `Workspace.problems` into groups and items with one-based positions, that it
 * filters, and that it counts before filtering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProblemsStateTest {

    private val root: Path = Path.of("/ws").toAbsolutePath()
    private val machine = root.resolve("sub/robot.kstates")
    private val descriptor = root.resolve("env.kenv.json")
    private val text = TextSnapshot("STATE \"a\" {\n  oops\n}\n", 4)

    private fun diagnostic(start: Int, severity: Severity) =
        Diagnostic(TextRange(start, start + 1), severity, "m$start", "code", DiagnosticSource.PARSER)

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

    @Test
    fun `problems are grouped by file with one-based positions, versions and relative paths`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val state = ProblemsState(scope)
        val workspace = FakeWorkspace(root)
        state.bindWorkspace(workspace)
        workspace.problems.value = problems
        advanceUntilIdle()

        val ui = state.state.value
        assertEquals(1, ui.errorCount)
        assertEquals(2, ui.warningCount)
        assertEquals(listOf("env.kenv.json", "sub/robot.kstates"), ui.groups.map { it.displayPath })
        val items = ui.groups[1].items
        assertEquals(listOf(1 to 3, 2 to 3, 2 to 4), items.map { it.line to it.column })
        assertEquals(4L, items.first().version)
        assertTrue(items.first().lineage === text.lineage)
        assertEquals(TextRange(2, 3), items.first().location.range)
        val unknown = ui.groups[0].items.single()
        assertEquals(1 to 1, unknown.line to unknown.column, "no snapshot: the origin")
        assertEquals(null, unknown.version)
        scope.cancel()
    }

    @Test
    fun `the filter hides severities but not the counts, and empty groups disappear`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val state = ProblemsState(scope)
        val workspace = FakeWorkspace(root)
        workspace.problems.value = problems
        state.bindWorkspace(workspace)

        state.setFilter(ProblemFilter(showWarnings = false, showInfos = false))
        val ui = state.state.value
        assertEquals(listOf(machine), ui.groups.map { it.path })
        assertEquals(listOf(Severity.ERROR), ui.groups.single().items.map { it.diagnostic.severity })
        assertEquals(2, ui.warningCount)
        assertEquals(ProblemFilter(showWarnings = false, showInfos = false), ui.filter)

        state.bindWorkspace(null)
        assertEquals(ProblemsUiState(filter = ui.filter), state.state.value, "unbinding keeps the filter")
        scope.cancel()
    }

    @Test
    fun `hints count as infos`() {
        val filter = ProblemFilter(showInfos = false)
        assertTrue(!ProblemsMapping.shows(filter, Severity.HINT))
        assertTrue(ProblemsMapping.shows(filter, Severity.ERROR))
    }
}
