package io.karpfen.ide.ui.workbench

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.karpfen.ide.ui.workbench.state.WorkbenchState

/**
 * The workbench window content (spec §8): Project tree (left), editor tabs with `CodeEditor` and the popups (centre),
 * Outline (right), Problems (bottom), status bar, dialogs; the empty state with "Open Folder…" and recent folders when
 * no folder is open. Provides the light/dark `KarpfenTheme` and `LocalEditorTheme` from the settings. Renders
 * [state]'s sub-states and calls its intents; contains no business logic.
 */
@Composable
public fun Workbench(state: WorkbenchState, modifier: Modifier = Modifier): Unit = TODO("WP-WORKBENCH: workbench layout")
