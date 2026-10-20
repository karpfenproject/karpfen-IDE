package io.karpfen.ide.ui.workbench.outline.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.ui.workbench.outline.OutlineUiState

/**
 * The Outline tool window (spec §8): outline tree; the node containing the caret is highlighted; clicking a node calls
 * [onNavigate] with `state.locationOf(node)` (versioned, so `WorkbenchState.navigateTo` maps it into the live text).
 */
@Composable
internal fun OutlineView(state: OutlineUiState, onNavigate: (Location) -> Unit, modifier: Modifier = Modifier): Unit =
    TODO("WP-WORKBENCH: outline view")
