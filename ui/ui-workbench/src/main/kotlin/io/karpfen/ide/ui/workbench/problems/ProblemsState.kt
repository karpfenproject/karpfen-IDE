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

/** Which severities the Problems view shows. This is its error, warning and info filter. */
public data class ProblemFilter(
    public val showErrors: Boolean = true,
    public val showWarnings: Boolean = true,
    public val showInfos: Boolean = true,
)

/**
 * One problem. It is a diagnostic of [path], with the one-based [line] and [column] that the view shows as `line:col`.
 *
 * @property version the version of the text that the diagnostic's range refers to, taken from `FileProblems.snapshot`.
 *   Null if unknown.
 * @property lineage the lineage of that text. Null if unknown.
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

    /**
     * Where a double-click navigates to. That is the diagnostic's range in the text it refers to. `navigateTo` maps it
     * into the live text.
     */
    public val location: Location get() = Location(path, diagnostic.range, version, lineage)
}

/** The problems of one file. [displayPath] is relative to the workspace root. */
public data class ProblemGroup(public val path: Path, public val displayPath: String, public val items: List<ProblemItem>)

/** What the Problems tool window shows. The counts are taken before filtering. */
public data class ProblemsUiState(
    public val groups: List<ProblemGroup> = emptyList(),
    public val filter: ProblemFilter = ProblemFilter(),
    public val errorCount: Int = 0,
    public val warningCount: Int = 0,
)

/**
 * The problems of the workspace, grouped by file. It collects `Workspace.problems` in [scope] and maps them through
 * [ProblemsMapping] into groups and items. Each item has a one-based `line:col` from `FileProblems.positionOf` and the
 * version and lineage of the file's snapshot. The class never merges diagnostics itself. `Workspace.problems` is the
 * single rule of core-workspace for that, and the status bar and `karpfen validate` share it.
 *
 * Binding a workspace is an operation of the mediator and is internal, so no view can bypass the coordinated flows of
 * the workbench.
 */
public class ProblemsState(private val scope: CoroutineScope) {

    private val problems = MutableStateFlow(ProblemsUiState())
    private var workspace: Workspace? = null
    private var binding: Job? = null

    /** What the Problems tool window shows. */
    public val state: StateFlow<ProblemsUiState> get() = problems.asStateFlow()

    /** Follows [workspace]. Null clears the problems. Only the mediator calls it. */
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

    /** Maps the current problems of the bound workspace under the current filter. Shows nothing without a workspace. */
    private fun refresh() {
        val filter = problems.value.filter
        problems.value = workspace?.let { ProblemsMapping.map(it.problems.value, it.root, filter) } ?: ProblemsUiState(filter = filter)
    }
}
