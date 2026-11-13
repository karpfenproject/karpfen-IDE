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
 * Maps `Workspace.problems` and the global findings of the last validation onto the [ProblemsUiState] of the Problems
 * view. It is pure. Both parts are lists of [FileProblems] and are mapped by the same code.
 *
 * There is one group per file, in the order of the files. The items keep the order of the diagnostics. Each item has
 * a one-based `line:col` from `FileProblems.positionOf` and the version and lineage of the file's snapshot. Items are
 * filtered by severity. The counts are those of both parts together, taken before filtering.
 *
 * The mapping only maps and filters. `Workspace.problems` combines the diagnostics. It is the single problems rule of
 * core-workspace. The Problems view, the status bar and the validation of the project all share it, so they always
 * agree. The validator of core-workspace decides which findings are global, so the mapping takes them as they are.
 */
internal object ProblemsMapping {

    /**
     * The Problems view of an open folder at [root]: the [local] problems of the workspace and the [global] findings of
     * the last validation, null before the first one, under [filter]. File paths are shown relative to [root].
     */
    fun map(local: WorkspaceProblems, global: List<FileProblems>?, root: Path, filter: ProblemFilter): ProblemsUiState =
        ProblemsUiState(
            local = groups(local.files, root, filter),
            global = global?.let { groups(it, root, filter) },
            filter = filter,
            errorCount = local.errorCount + global.orEmpty().sumOf { it.errorCount },
            warningCount = local.warningCount + global.orEmpty().sumOf { it.warningCount },
            hasFolder = true,
        )

    /** True if [filter] shows diagnostics of [severity]. Hints count as infos. */
    fun shows(filter: ProblemFilter, severity: Severity): Boolean = when (severity) {
        Severity.ERROR -> filter.showErrors
        Severity.WARNING -> filter.showWarnings
        Severity.INFO, Severity.HINT -> filter.showInfos
    }

    /** The groups of [files] under [filter], in their order, without those whose items are all filtered out. */
    private fun groups(files: List<FileProblems>, root: Path, filter: ProblemFilter): List<ProblemGroup> =
        files.mapNotNull { group(it, root, filter) }

    /** The group of [file] under [filter], or null if no item passes the filter. */
    private fun group(file: FileProblems, root: Path, filter: ProblemFilter): ProblemGroup? {
        val items = file.diagnostics.filter { shows(filter, it.severity) }.map { item(file, it) }
        return if (items.isEmpty()) null else ProblemGroup(file.path, displayPath(file.path, root), items)
    }

    /** The item of [diagnostic] of [file], with its one-based position and the version and lineage of the snapshot. */
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
