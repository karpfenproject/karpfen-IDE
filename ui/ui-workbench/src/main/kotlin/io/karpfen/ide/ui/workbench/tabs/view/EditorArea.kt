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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.ui.editor.popup.EditorPopups
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.view.CodeEditor
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.foundation.widgets.TabStrip
import io.karpfen.ide.ui.workbench.settings.ZoomStep
import io.karpfen.ide.ui.workbench.statusbar.view.EditorZoomBar
import io.karpfen.ide.ui.workbench.tabs.ConflictBanner
import io.karpfen.ide.ui.workbench.tabs.EditorTab
import java.nio.file.Path

/**
 * The editor area. It shows the `TabStrip` of [tabs], whose items come from [tabItemOf], which marks tabs in conflict.
 * Below the strip it shows the conflict banner of the active tab, rendered by [ConflictBanner] from
 * `EditorTab.conflict`. For a modified file the banner offers Reload and Keep mine, and for a deleted file Close and
 * Keep mine. Each button reports a `ConflictResolution`, and both Reload and Close report RELOAD. The `CodeEditor` of
 * [activeEditor] with the editor popups fills the rest. Below an editor a bar holds the zoom controls for the font
 * size [editorFontSize] of the editors, and their clicks go to [onZoomEditor].
 *
 * The editor and its popups are drawn at the density of the window, not at the zoomed density of the workbench around
 * them. The size of the editor's text therefore depends only on its font size. The tab strip, the banner and the zoom
 * bar belong to the workbench and follow its zoom.
 *
 * The editor is composed inside `key(activePath)`. So switching tabs disposes the composition of the old editor and
 * its popups, the text fields of the popups included. State that must survive a tab switch, such as the replacement
 * text of find and replace, therefore lives in the editor's models and not in the composables.
 *
 * The mediator collects the navigation requests of the editors. They are not relayed through this view. The callbacks
 * call the non-suspending `WorkbenchState` intents directly, and no coroutine is launched here.
 *
 * @param onClose closes a tab through `WorkbenchState.closeTab`, which runs the unsaved-changes flow.
 */
@Composable
internal fun EditorArea(
    tabs: List<EditorTab>,
    activePath: Path?,
    activeEditor: CodeEditorState?,
    onActivate: (Path) -> Unit,
    onClose: (Path) -> Unit,
    onResolveConflict: (Path, ConflictResolution) -> Unit,
    editorFontSize: Float,
    onZoomEditor: (ZoomStep) -> Unit,
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
                CompositionLocalProvider(LocalDensity provides (LocalWindowDensity.current ?: LocalDensity.current)) {
                    key(activePath) {
                        CodeEditor(
                            state = activeEditor,
                            modifier = Modifier.fillMaxSize().testTag(EditorAreaTestTags.EDITOR),
                            overlays = popups,
                        )
                    }
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
        if (activeEditor != null && activePath != null) EditorZoomBar(editorFontSize, onZoomEditor, EditorAreaTestTags.ZOOM_BAR)
    }
}

/**
 * The density of the window before the workbench's zoom is applied, or null outside a workbench. The workbench
 * provides it, and the editor area draws the editor at this density, so the zoom of the workbench does not reach the
 * editor's text.
 */
internal val LocalWindowDensity = staticCompositionLocalOf<Density?> { null }

/** The conflict [banner] with one button per action. Each button reports its resolution to [onResolve]. */
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
