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
 * The Outline tool window (spec §8): outline tree; the node containing the caret is highlighted (selected); clicking a
 * node (or Enter / double-click) calls [onNavigate] with `state.locationOf(node)` (versioned, so
 * `WorkbenchState.navigateTo` maps it into the live text). Nodes start expanded; collapsing is view-local state.
 */
@Composable
internal fun OutlineView(state: OutlineUiState, onNavigate: (Location) -> Unit, modifier: Modifier = Modifier) {
    if (state.nodes.isEmpty()) {
        Box(modifier.fillMaxSize()) {
            val text = if (state.path == null) "No file open" else "Nothing to outline"
            Text(text, color = KarpfenTheme.colors.textMuted, style = KarpfenTheme.typography.uiSmall, modifier = Modifier.align(Alignment.Center))
        }
        return
    }
    val nodes = remember(state.nodes) { OutlineTreeNodes(state.nodes) }
    var collapsed by remember(state.path) { mutableStateOf(emptySet<OutlineKey>()) }
    fun navigate(key: OutlineKey) {
        nodes.nodeOf(key)?.let(state::locationOf)?.let(onNavigate)
    }
    TreeView(
        roots = nodes.roots,
        expanded = nodes.expandable - collapsed,
        selected = nodes.keyOf(state.highlighted),
        onToggleExpanded = { key -> collapsed = if (key in collapsed) collapsed - key else collapsed + key },
        onSelect = ::navigate,
        onOpen = ::navigate,
        modifier = modifier.fillMaxSize(),
    )
}
