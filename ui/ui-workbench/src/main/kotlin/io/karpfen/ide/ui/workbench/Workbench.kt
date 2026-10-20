package io.karpfen.ide.ui.workbench

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.karpfen.ide.ui.workbench.state.WorkbenchState

/**
 * The workbench window content (spec §8): Project tree (left), editor tabs with `CodeEditor` and the popups (centre),
 * Outline (right), Problems (bottom), status bar, dialogs; the empty state with "Open Folder…" and recent folders when
 * no folder is open. Provides the light/dark `KarpfenTheme` and `LocalEditorTheme` from `state.layout` (theme) and
 * lays out the tool windows from it. Renders [state]'s sub-states (each feature's view from its `view` package) and
 * calls its intents and the sub-states' public UI intents; contains no business logic.
 */
@Composable
public fun Workbench(state: WorkbenchState, modifier: Modifier = Modifier): Unit = TODO("WP-WORKBENCH: workbench layout")
