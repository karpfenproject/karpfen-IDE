package io.karpfen.ide.ui.workbench.problems

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.TextLineage
import io.karpfen.ide.core.workspace.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.file.Path

/** Which severities the Problems view shows (its error, warning and info filter). */
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
 * Problems of the workspace grouped by file: maps `Workspace.problems` (the single rule of core-workspace, shared with
 * the status bar and `karpfen validate`) into groups and items with one-based `line:col` (`FileProblems.positionOf`)
 * and the snapshot's version and lineage; it never merges diagnostics itself (collected in [scope]). The binding is a
 * mediator operation and internal, so no view can bypass the workbench's coordinated flows. The mapping is
 * [ProblemsMapping]'s.
 */
public class ProblemsState(private val scope: CoroutineScope) {

    private val problems = MutableStateFlow(ProblemsUiState())
    private var workspace: Workspace? = null
    private var binding: Job? = null

    /** What the Problems tool window shows. */
    public val state: StateFlow<ProblemsUiState> get() = problems.asStateFlow()

    /** Follows [workspace] (null clears). Mediator only. */
    internal fun bindWorkspace(workspace: Workspace?) {
        binding?.cancel()
        this.workspace = workspace
        refresh()
        binding = workspace?.let { bound ->
            scope.launch { bound.problems.collect { refresh() } }
        }
    }

    /** Changes the severity filter. */
    public fun setFilter(filter: ProblemFilter) {
        problems.update { it.copy(filter = filter) }
        refresh()
    }

    /** Maps the bound workspace's current problems under the current filter (nothing without a workspace). */
    private fun refresh() {
        val filter = problems.value.filter
        problems.value = workspace?.let { ProblemsMapping.map(it.problems.value, it.root, filter) } ?: ProblemsUiState(filter = filter)
    }
}
