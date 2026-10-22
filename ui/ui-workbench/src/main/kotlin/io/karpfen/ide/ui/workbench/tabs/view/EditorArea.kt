package io.karpfen.ide.ui.workbench.tabs.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.ui.editor.popup.EditorPopups
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.view.CodeEditor
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.foundation.widgets.TabStrip
import io.karpfen.ide.ui.workbench.tabs.ConflictBanner
import io.karpfen.ide.ui.workbench.tabs.EditorTab
import java.nio.file.Path

/**
 * The editor area: `TabStrip` of [tabs] (items from [tabItemOf], which marks tabs in conflict), the external-change
 * banner of the active tab rendered from `EditorTab.conflict` ([ConflictBanner]: MODIFIED: Reload / Keep mine; DELETED:
 * Close / Keep mine; both report a `ConflictResolution`, RELOAD for Reload and Close), and the `CodeEditor` of
 * [activeEditor] with the editor popups. Navigation requests of the editors are collected by the mediator, not relayed
 * through this view. The callbacks call the non-suspending `WorkbenchState` intents directly (no coroutine launched
 * here).
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
) {
    val colors = KarpfenTheme.colors
    val popups = remember { EditorPopups() }
    val byId = tabs.associateBy { it.path.toString() }
    Column(modifier.fillMaxSize().background(colors.window).testTag(EditorAreaTestTags.AREA)) {
        if (tabs.isNotEmpty()) {
            TabStrip(
                tabs = tabs.map(::tabItemOf),
                selectedId = activePath?.toString(),
                onSelect = { id -> byId[id]?.let { onActivate(it.path) } },
                onClose = { id -> byId[id]?.let { onClose(it.path) } },
            )
        }
        val activeTab = tabs.firstOrNull { it.path == activePath }
        activeTab?.let(ConflictBanner::of)?.let { banner -> ConflictBannerView(banner) { onResolveConflict(activeTab.path, it) } }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (activeEditor != null && activePath != null) {
                key(activePath) {
                    CodeEditor(
                        state = activeEditor,
                        modifier = Modifier.fillMaxSize().testTag(EditorAreaTestTags.EDITOR),
                        overlays = popups,
                    )
                }
            } else {
                Text(
                    "Open a file from the Project tree",
                    color = colors.textMuted,
                    style = KarpfenTheme.typography.uiSmall,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/** The external-change [banner] with one button per action, each reporting its resolution to [onResolve]. */
@Composable
private fun ConflictBannerView(banner: ConflictBanner, onResolve: (ConflictResolution) -> Unit) {
    val colors = KarpfenTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.selection).padding(horizontal = 8.dp).testTag(EditorAreaTestTags.CONFLICT_BANNER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(banner.message, color = colors.text, style = KarpfenTheme.typography.uiSmall, modifier = Modifier.weight(1f))
        banner.actions.forEach { action ->
            TextButton(
                onClick = { onResolve(action.resolution) },
                modifier = Modifier.testTag(EditorAreaTestTags.conflictButton(action.label)),
            ) { Text(action.label) }
        }
    }
}
