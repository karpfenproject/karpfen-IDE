package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.document.Diagnostic
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

/** One problem: a diagnostic of [path] with its one-based [line] and [column] for display (`line:col`). */
public data class ProblemItem(
    public val path: Path,
    public val diagnostic: Diagnostic,
    public val line: Int,
    public val column: Int,
)

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
 * Problems of the workspace grouped by file (spec §8): live session diagnostics for open documents, otherwise the
 * syntax diagnostics of the analyzed file plus the project diagnostics of the latest analysis (collected in [scope]).
 */
public class ProblemsState(private val scope: CoroutineScope) {

    /** What the Problems tool window shows. */
    public val state: StateFlow<ProblemsUiState> get() = TODO("WP-WORKBENCH: problems state")

    /** Follows [workspace] (null clears). */
    public fun bind(workspace: Workspace?): Unit = TODO("WP-WORKBENCH: bind problems to the workspace")

    /** Changes the severity filter. */
    public fun setFilter(filter: ProblemFilter): Unit = TODO("WP-WORKBENCH: filter")
}
