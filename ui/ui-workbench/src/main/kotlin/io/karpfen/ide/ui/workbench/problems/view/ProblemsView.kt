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
import io.karpfen.ide.ui.workbench.problems.ProblemFilter
import io.karpfen.ide.ui.workbench.problems.ProblemsUiState

/**
 * The Problems tool window. While a folder is open it shows two top nodes, Local for what is found while typing and
 * Global for what the last validation of the whole project found. Below each the problems are grouped by file, each
 * with its severity icon, its message and its `line:col`. A row of filter chips switches errors, warnings and infos on
 * and off in both parts. The chips show the counts of both parts together, taken before filtering. A double-click or
 * Enter on a problem calls [onNavigate] with `ProblemItem.location`, which carries the text version. On a top node or
 * a group, they expand or collapse it. Top nodes and groups start expanded. Which of them are collapsed is local view
 * state. Without an open folder the view reads "No problems".
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
            FilterToggle("errors", "Errors (${state.errorCount})", filter.showErrors) {
                onFilterChange(filter.copy(showErrors = it))
            }
            FilterToggle("warnings", "Warnings (${state.warningCount})", filter.showWarnings) {
                onFilterChange(filter.copy(showWarnings = it))
            }
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
                    expanded = nodes.expandable - collapsed,
                    selected = selected,
                    onToggleExpanded = ::toggle,
                    onSelect = { selected = it },
                    onOpen = { key ->
                        val item = nodes.itemOf(key)
                        if (item != null) onNavigate(item.location) else if (key in nodes.expandable) toggle(key)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** The severity filter chip [name] with the text [label]. It is [selected] while that severity is shown. */
@Composable
private fun FilterToggle(name: String, label: String, selected: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        label = { Text(label, style = KarpfenTheme.typography.uiSmall) },
        modifier = Modifier.testTag(ProblemsViewTestTags.filter(name)),
    )
}
