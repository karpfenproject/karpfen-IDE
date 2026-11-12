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
package io.karpfen.ide.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.ui.editor.theme.LocalEditorTheme
import io.karpfen.ide.ui.editor.theme.darkEditorTheme
import io.karpfen.ide.ui.editor.theme.lightEditorTheme
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.dialog.view.ModalDialogLayer
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import io.karpfen.ide.ui.workbench.layout.isDark
import io.karpfen.ide.ui.workbench.outline.view.OutlineView
import io.karpfen.ide.ui.workbench.output.view.OutputView
import io.karpfen.ide.ui.workbench.problems.view.ProblemsView
import io.karpfen.ide.ui.workbench.projecttree.view.ProjectTreeView
import io.karpfen.ide.ui.workbench.runtime.view.RuntimeToolWindow
import io.karpfen.ide.ui.workbench.state.WorkbenchState
import io.karpfen.ide.ui.workbench.statusbar.view.StatusBarActions
import io.karpfen.ide.ui.workbench.statusbar.view.WorkbenchStatusBar
import io.karpfen.ide.ui.workbench.tabs.EditorViewMode
import io.karpfen.ide.ui.workbench.tabs.view.EditorArea
import io.karpfen.ide.ui.workbench.tabs.view.LocalWindowDensity
import io.karpfen.ide.ui.workbench.welcome.view.WelcomeView
import java.nio.file.Path

/**
 * The content of the workbench window. The Project tree sits on the left. The editor tabs with the `CodeEditor` and
 * its popups fill the centre. The Outline sits on the right. Problems, the Language Tools Output and the Runtime tool
 * window sit at the bottom over the whole width, above the status bar. Dialogs are shown over all of it. While no folder is open, the window
 * shows the welcome view with "Open Folder…" and the recent folders instead.
 *
 * A click on the status bar's Problems label shows or hides the Problems tool window. A click on its indicator of
 * unseen language tool problems shows the Language Tools Output. A click on its runtime status shows the Runtime tool
 * window.
 *
 * The workbench is drawn at the scale of `IdeSettings.uiScale`. The composable provides a density that is the
 * window's density times that scale, so the text and everything that holds it grow and shrink together. The zoom
 * controls at the right end of the status bar change the scale.
 *
 * The editor is the exception. Its text has a size of its own, the font size of the editors, with controls in the bar
 * below the editor. The two sizes are independent, so the editor is drawn at the window's own density, which
 * [LocalWindowDensity] hands to the editor area. Zooming the workbench leaves the editor's text as it is, and changing
 * the editor's font size changes nothing around it.
 *
 * The composable provides the light or dark `KarpfenTheme` and the matching `LocalEditorTheme` from the theme in
 * `state.layout`. It lays out the tool windows from the same layout with size-based `SplitPane`s. The fixed size of
 * each split is the tool window's persisted size in dp, and a drag of the divider reports the new size back through
 * `layout.resizeToolWindow`. The Project tool window is the first pane of its split. Outline, Problems, Output and
 * Runtime are the second pane of theirs.
 *
 * It renders the sub-states of [state], each through the view in that feature's `view` package. It only calls the
 * non-suspending intents of [state] and the public UI intents of the sub-states. It contains no business logic and
 * launches no coroutines for intents. The pending dialog is modal for both the pointer and the keyboard, which
 * [ModalDialogLayer] enforces.
 */
@Composable
public fun Workbench(state: WorkbenchState, modifier: Modifier = Modifier) {
    val layout by state.layout.state.collectAsState()
    val settings by state.settings.collectAsState()
    val dark = layout.theme.isDark(isSystemInDarkTheme())
    val window = LocalDensity.current
    val zoomed = remember(window, settings.uiScale) { Density(window.density * settings.uiScale, window.fontScale) }
    KarpfenTheme(darkTheme = dark) {
        CompositionLocalProvider(
            LocalEditorTheme provides if (dark) darkEditorTheme() else lightEditorTheme(),
            LocalDensity provides zoomed,
            LocalWindowDensity provides window,
        ) {
            val workspace by state.workspace.collectAsState()
            val dialog by state.dialogs.current.collectAsState()
            ModalDialogLayer(dialog, state.dialogs::respond, modifier.fillMaxSize().background(KarpfenTheme.colors.window)) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (workspace == null) {
                            WelcomeView(
                                recentFolders = settings.recentFolders,
                                onOpenFolder = { state.chooseAndOpenFolder() },
                                onOpenRecent = { state.openFolder(Path.of(it)) },
                            )
                        } else {
                            WorkbenchLayout(
                                layout = layout,
                                layoutState = state.layout,
                                panes = WorkbenchPanes(
                                    project = { ProjectPane(state) },
                                    editor = { EditorPane(state) },
                                    outline = { OutlinePane(state) },
                                    problems = { ProblemsPane(state) },
                                    output = { OutputPane(state) },
                                    runtime = { RuntimePane(state) },
                                ),
                            )
                        }
                    }
                    val status by state.statusBar.state.collectAsState()
                    WorkbenchStatusBar(
                        status,
                        uiScale = settings.uiScale,
                        actions = remember(state) {
                            StatusBarActions(
                                onToggleProblems = { state.layout.toggleToolWindow(ToolWindowId.PROBLEMS) },
                                onShowOutput = { state.layout.showToolWindow(ToolWindowId.OUTPUT) },
                                onShowRuntime = { state.layout.showToolWindow(ToolWindowId.RUNTIME) },
                                onZoomUi = { state.zoomUi(it) },
                            )
                        },
                    )
                }
            }
        }
    }
}

/** The Project tool window over [state]'s project tree. It stays empty while no tree is loaded. */
@Composable
private fun ProjectPane(state: WorkbenchState) {
    val tree = state.projectTree
    val workspaceTree by tree.tree.collectAsState()
    val expanded by tree.expanded.collectAsState()
    val selected by tree.selected.collectAsState()
    workspaceTree?.let {
        ProjectTreeView(
            tree = it,
            expanded = expanded,
            selected = selected,
            menuFor = tree::menuFor,
            onToggle = tree::toggle,
            onSelect = tree::select,
            onOpen = { path -> state.openNode(path) },
            onCommand = { command -> state.runTreeCommand(command) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The editor area with [state]'s tabs and active editor. */
@Composable
private fun EditorPane(state: WorkbenchState) {
    val tabs by state.tabs.tabs.collectAsState()
    val active by state.tabs.active.collectAsState()
    val activeEditor by state.tabs.activeEditor.collectAsState()
    val viewModes by state.tabs.viewModes.collectAsState()
    val settings by state.settings.collectAsState()
    EditorArea(
        tabs = tabs,
        activePath = active,
        activeEditor = activeEditor,
        onActivate = state.tabs::activate,
        onClose = { path -> state.closeTab(path) },
        onResolveConflict = { path, resolution -> state.resolveConflict(path, resolution) },
        editorFontSize = settings.editorFontSize,
        onZoomEditor = { state.zoomEditor(it) },
        viewMode = active?.let(viewModes::get) ?: EditorViewMode.TEXT,
        onViewMode = { mode -> active?.let { state.tabs.setViewMode(it, mode) } },
        onSelectInText = { range -> active?.let { state.navigateTo(Location(it, range)) } },
    )
}

/** The Outline tool window of [state]'s active document. */
@Composable
private fun OutlinePane(state: WorkbenchState) {
    val outline by state.outline.state.collectAsState()
    OutlineView(outline, onNavigate = { state.navigateTo(it) })
}

/** The Language Tools Output tool window over [state]'s output. */
@Composable
private fun OutputPane(state: WorkbenchState) {
    val output by state.output.state.collectAsState()
    OutputView(
        output,
        onFollowChange = state.output::setFollow,
        onClear = state.output::clear,
        readingPosition = state.output.readingPosition,
        onReadingPositionChange = state.output::keepReadingPosition,
    )
}

/** The Runtime tool window of [state]'s workspace. It stays empty while no workspace is open. */
@Composable
private fun RuntimePane(state: WorkbenchState) {
    val runtime by state.runtime.collectAsState()
    val workspace by state.workspace.collectAsState()
    val root = workspace?.root ?: return
    runtime?.let { RuntimeToolWindow(it, root, onNavigate = { location -> state.navigateTo(location) }) }
}

/** The Problems tool window of [state]'s workspace. */
@Composable
private fun ProblemsPane(state: WorkbenchState) {
    val problems by state.problems.state.collectAsState()
    ProblemsView(problems, onFilterChange = state.problems::setFilter, onNavigate = { state.navigateTo(it) })
}
