package io.karpfen.ide.ui.workbench.outline

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.ui.workbench.state.OutlineUiState

/** The Outline tool window (spec §8): outline tree; the node containing the caret is highlighted; click navigates. */
@Composable
internal fun OutlineView(state: OutlineUiState, onSelect: (OutlineNode) -> Unit, modifier: Modifier = Modifier): Unit =
    TODO("WP-WORKBENCH: outline view")
