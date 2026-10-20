package io.karpfen.ide.ui.workbench.tabs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.workbench.state.EditorTab
import java.nio.file.Path

/**
 * The editor area (spec §8): `TabStrip` of [tabs], the external-change banner (Reload / Keep mine) of the active tab,
 * and the `CodeEditor` of [activeEditor] with the editor popups.
 */
@Composable
internal fun EditorArea(
    tabs: List<EditorTab>,
    activePath: Path?,
    activeEditor: CodeEditorState?,
    onActivate: (Path) -> Unit,
    onClose: (Path) -> Unit,
    onResolveConflict: (Path, ConflictResolution) -> Unit,
    onNavigate: (Location) -> Unit,
    modifier: Modifier = Modifier,
): Unit = TODO("WP-WORKBENCH: editor area")
