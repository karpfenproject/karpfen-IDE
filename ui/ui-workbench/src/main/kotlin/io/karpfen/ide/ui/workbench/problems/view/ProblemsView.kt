package io.karpfen.ide.ui.workbench.problems.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.foundation.widgets.TreeView
import io.karpfen.ide.ui.workbench.WorkbenchTestTags
import io.karpfen.ide.ui.workbench.problems.ProblemFilter
import io.karpfen.ide.ui.workbench.problems.ProblemsUiState

/**
 * The Problems tool window (spec §8): problems grouped by file with severity icon, message and `line:col`; error /
 * warning / info filter (with the counts before filtering); double-click or Enter calls [onNavigate] with the item's
 * `ProblemItem.location` (versioned). Groups start expanded; collapsing is view-local state.
 */
@Composable
internal fun ProblemsView(
    state: ProblemsUiState,
    onFilterChange: (ProblemFilter) -> Unit,
    onNavigate: (Location) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filter = state.filter
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterToggle("errors", "Errors (${state.errorCount})", filter.showErrors) { onFilterChange(filter.copy(showErrors = it)) }
            FilterToggle("warnings", "Warnings (${state.warningCount})", filter.showWarnings) { onFilterChange(filter.copy(showWarnings = it)) }
            FilterToggle("infos", "Infos", filter.showInfos) { onFilterChange(filter.copy(showInfos = it)) }
        }
        val nodes = remember(state) { ProblemsTreeNodes(state) }
        var collapsed by remember { mutableStateOf(emptySet<ProblemKey>()) }
        var selected by remember { mutableStateOf<ProblemKey?>(null) }
        fun toggle(key: ProblemKey) {
            collapsed = if (key in collapsed) collapsed - key else collapsed + key
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (nodes.roots.isEmpty()) {
                Text(
                    "No problems",
                    color = KarpfenTheme.colors.textMuted,
                    style = KarpfenTheme.typography.uiSmall,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                TreeView(
                    roots = nodes.roots,
                    expanded = nodes.groups - collapsed,
                    selected = selected,
                    onToggleExpanded = ::toggle,
                    onSelect = { selected = it },
                    onOpen = { key -> nodes.itemOf(key)?.let { onNavigate(it.location) } ?: toggle(key) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun FilterToggle(name: String, label: String, selected: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        label = { Text(label, style = KarpfenTheme.typography.uiSmall) },
        modifier = Modifier.testTag(WorkbenchTestTags.problemsFilter(name)),
    )
}
