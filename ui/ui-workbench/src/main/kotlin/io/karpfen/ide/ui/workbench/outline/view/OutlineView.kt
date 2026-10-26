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
package io.karpfen.ide.ui.workbench.outline.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.foundation.widgets.TreeView
import io.karpfen.ide.ui.workbench.outline.OutlineUiState

/**
 * The Outline tool window: outline tree; the node containing the caret is highlighted (selected); every click on a node
 * — also on the highlighted one, to get back to it after scrolling the editor away — and Enter (or a double-click) call
 * [onNavigate] with `state.locationOf(node)` (versioned, so `WorkbenchState.navigateTo` maps it into the live text).
 * The arrow keys browse: they move the selection without navigating, so the focus stays in the outline; the browsed
 * node is view-local and gives way to the highlight whenever the caret's node changes. Nodes start expanded; collapsing
 * is view-local state too.
 */
@Composable
internal fun OutlineView(state: OutlineUiState, onNavigate: (Location) -> Unit, modifier: Modifier = Modifier) {
    if (state.nodes.isEmpty()) {
        Box(modifier.fillMaxSize()) {
            val text = if (state.path == null) "No file open" else "Nothing to outline"
            Text(
                text,
                color = KarpfenTheme.colors.textMuted,
                style = KarpfenTheme.typography.uiSmall,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }
    val nodes = remember(state.nodes) { OutlineTreeNodes(state.nodes) }
    var collapsed by remember(state.path) { mutableStateOf(emptySet<OutlineKey>()) }
    val highlighted = nodes.keyOf(state.highlighted)
    var browsed by remember(nodes, highlighted) { mutableStateOf(highlighted) }
    fun navigate(key: OutlineKey) {
        nodes.nodeOf(key)?.let(state::locationOf)?.let(onNavigate)
    }
    TreeView(
        roots = nodes.roots,
        expanded = nodes.expandable - collapsed,
        selected = browsed,
        onToggleExpanded = { key -> collapsed = if (key in collapsed) collapsed - key else collapsed + key },
        onSelect = { key -> browsed = key },
        onOpen = ::navigate,
        modifier = modifier.fillMaxSize(),
        onClick = ::navigate,
    )
}
