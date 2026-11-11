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
 * The Outline tool window. It shows the outline tree and selects the node that contains the caret.
 *
 * Every click on a node calls [onNavigate] with `state.locationOf(node)`. That also holds for the highlighted node, so
 * the user can get back to it after scrolling the editor away. Enter and a double-click navigate too. The location
 * carries the parse version, so `WorkbenchState.navigateTo` can map it into the live text.
 *
 * The arrow keys browse. They move the selection without navigating, so the focus stays in the outline. The browsed
 * node is local to the view. It gives way to the highlight whenever the node that contains the caret changes. Nodes
 * start expanded. Which nodes are collapsed is local view state too.
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
