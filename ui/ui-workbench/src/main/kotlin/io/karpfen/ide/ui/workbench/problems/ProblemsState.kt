package io.karpfen.ide.ui.workbench.problems

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.TextLineage
import io.karpfen.ide.core.workspace.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/** Which severities the Problems view shows (spec §8: error/warning filter). */
public data class ProblemFilter(
    public val showErrors: Boolean = true,
    public val showWarnings: Boolean = true,
    public val showInfos: Boolean = true,
)

/**
 * One problem: a diagnostic of [path] with its one-based [line] and [column] for display (`line:col`).
 *
 * @property version the version of the text the diagnostic's range refers to (`FileProblems.snapshot`), null if
 *   unknown.
 * @property lineage that text's lineage, null if unknown.
 */
public data class ProblemItem(
    public val path: Path,
    public val diagnostic: Diagnostic,
    public val line: Int,
    public val column: Int,
    public val version: Long? = null,
    public val lineage: TextLineage? = null,
) {
    init {
        require(lineage == null || version != null) { "a lineage needs a version" }
    }

    /** Where double-click navigates: the diagnostic's range in the text it refers to (mapped by `navigateTo`). */
    public val location: Location get() = Location(path, diagnostic.range, version, lineage)
}

/** The problems of one file; [displayPath] is relative to the workspace root. */
public data class ProblemGroup(public val path: Path, public val displayPath: String, public val items: List<ProblemItem>)

/** What the Problems tool window shows; counts are before filtering. */
public data class ProblemsUiState(
    public val groups: List<ProblemGroup> = emptyList(),
    public val filter: ProblemFilter = ProblemFilter(),
    public val errorCount: Int = 0,
    public val warningCount: Int = 0,
)

/**
 * Problems of the workspace grouped by file (spec §8): maps `Workspace.problems` (the single rule of core-workspace,
 * shared with the status bar and `karpfen validate`) into groups and items with one-based `line:col`
 * (`FileProblems.positionOf`) and the snapshot's version and lineage; it never merges diagnostics itself (collected in
 * [scope]). The binding is a mediator operation and internal (decision 31 of the work-package plan).
 */
public class ProblemsState(private val scope: CoroutineScope) {

    /** What the Problems tool window shows. */
    public val state: StateFlow<ProblemsUiState> get() = TODO("WP-WORKBENCH: problems state")

    /** Follows [workspace] (null clears). Mediator only. */
    internal fun bindWorkspace(workspace: Workspace?): Unit = TODO("WP-WORKBENCH: bind problems to the workspace")

    /** Changes the severity filter. */
    public fun setFilter(filter: ProblemFilter): Unit = TODO("WP-WORKBENCH: filter")
}
