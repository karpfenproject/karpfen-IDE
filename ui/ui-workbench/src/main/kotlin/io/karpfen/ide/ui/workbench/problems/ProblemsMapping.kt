package io.karpfen.ide.ui.workbench.problems

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.problems.FileProblems
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import java.nio.file.Path

/**
 * Maps `Workspace.problems` onto the Problems view's [ProblemsUiState] (spec §8): one group per file in the order of
 * [WorkspaceProblems.files], items in diagnostic order with one-based `line:col` from `FileProblems.positionOf` and the
 * snapshot's version and lineage, filtered by severity; the counts are the workspace's, before filtering. Only maps
 * and filters — the diagnostics are combined by the one problems rule of core-workspace (decision 17). Pure.
 */
internal object ProblemsMapping {

    fun map(problems: WorkspaceProblems, root: Path, filter: ProblemFilter): ProblemsUiState = ProblemsUiState(
        groups = problems.files.mapNotNull { group(it, root, filter) },
        filter = filter,
        errorCount = problems.errorCount,
        warningCount = problems.warningCount,
    )

    /** True if [filter] shows diagnostics of [severity] (hints count as infos). */
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

    private fun displayPath(path: Path, root: Path): String =
        if (path.startsWith(root) && path != root) root.relativize(path).joinToString("/") else path.toString()
}
