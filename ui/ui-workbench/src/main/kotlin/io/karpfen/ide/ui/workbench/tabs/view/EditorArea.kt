package io.karpfen.ide.ui.workbench.tabs.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.workbench.tabs.EditorTab
import java.nio.file.Path

/**
 * The editor area (spec §8): `TabStrip` of [tabs], the external-change banner (Reload / Keep mine) of the active tab,
 * and the `CodeEditor` of [activeEditor] with the editor popups. Navigation requests of the editors are collected by
 * the mediator, not relayed through this view.
 *
 * @param onClose closes a tab through `WorkbenchState.closeTab` (unsaved-changes flow).
 */
@Composable
internal fun EditorArea(
    tabs: List<EditorTab>,
    activePath: Path?,
    activeEditor: CodeEditorState?,
    onActivate: (Path) -> Unit,
    onClose: (Path) -> Unit,
    onResolveConflict: (Path, ConflictResolution) -> Unit,
    modifier: Modifier = Modifier,
): Unit = TODO("WP-WORKBENCH: editor area")
