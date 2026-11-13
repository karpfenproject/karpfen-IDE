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
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.problems.FileProblems
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import java.nio.file.Path

/**
 * Maps `Workspace.problems` onto the [ProblemsUiState] of the Problems view. It is pure.
 *
 * There is one group per file, in the order of [WorkspaceProblems.files]. The items keep the order of the diagnostics.
 * Each item has a one-based `line:col` from `FileProblems.positionOf` and the version and lineage of the file's
 * snapshot. Items are filtered by severity. The counts are those of the workspace, taken before filtering.
 *
 * The mapping only maps and filters. `Workspace.problems` combines the diagnostics. It is the single problems rule of
 * core-workspace. The Problems view, the status bar and the validation of the project all share it, so they always
 * agree.
 */
internal object ProblemsMapping {

    /** The Problems view of [problems] under [filter], with file paths shown relative to the workspace [root]. */
    fun map(problems: WorkspaceProblems, root: Path, filter: ProblemFilter): ProblemsUiState = ProblemsUiState(
        groups = problems.files.mapNotNull { group(it, root, filter) },
        filter = filter,
        errorCount = problems.errorCount,
        warningCount = problems.warningCount,
    )

    /** True if [filter] shows diagnostics of [severity]. Hints count as infos. */
    fun shows(filter: ProblemFilter, severity: Severity): Boolean = when (severity) {
        Severity.ERROR -> filter.showErrors
        Severity.WARNING -> filter.showWarnings
        Severity.INFO, Severity.HINT -> filter.showInfos
    }

    private fun group(file: FileProblems, root: Path, filter: ProblemFilter): ProblemGroup? {
        val items = file.diagnostics.filter { shows(filter, it.severity) }.map { item(file, it) }
        return if (items.isEmpty()) null else ProblemGroup(file.path, displayPath(file.path, root), items)
    }

    private fun item(file: FileProblems, diagnostic: Diagnostic): ProblemItem {
        val position = file.positionOf(diagnostic)
        return ProblemItem(
            path = file.path,
            diagnostic = diagnostic,
            line = position.line + 1,
            column = position.column + 1,
            version = file.snapshot?.version,
            lineage = file.snapshot?.lineage,
        )
    }

    /**
     * [path] relative to [root] with `/` separators. The root itself and paths outside the root are shown as whole
     * paths.
     */
    private fun displayPath(path: Path, root: Path): String =
        if (path.startsWith(root) && path != root) root.relativize(path).joinToString("/") else path.toString()
}
