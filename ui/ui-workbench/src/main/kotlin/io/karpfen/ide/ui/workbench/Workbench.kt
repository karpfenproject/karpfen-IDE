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
import androidx.compose.ui.Modifier
import io.karpfen.ide.ui.editor.theme.LocalEditorTheme
import io.karpfen.ide.ui.editor.theme.darkEditorTheme
import io.karpfen.ide.ui.editor.theme.lightEditorTheme
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.dialog.view.ModalDialogLayer
import io.karpfen.ide.ui.workbench.layout.isDark
import io.karpfen.ide.ui.workbench.outline.view.OutlineView
import io.karpfen.ide.ui.workbench.problems.view.ProblemsView
import io.karpfen.ide.ui.workbench.projecttree.view.ProjectTreeView
import io.karpfen.ide.ui.workbench.state.WorkbenchState
import io.karpfen.ide.ui.workbench.statusbar.view.WorkbenchStatusBar
import io.karpfen.ide.ui.workbench.tabs.view.EditorArea
import io.karpfen.ide.ui.workbench.welcome.view.WelcomeView
import java.nio.file.Path

/**
 * The workbench window content (spec §8): Project tree (left), editor tabs with `CodeEditor` and the popups (centre),
 * Outline (right), Problems (bottom), status bar, dialogs; the empty state with "Open Folder…" and recent folders when
 * no folder is open. Provides the light/dark `KarpfenTheme` and `LocalEditorTheme` from `state.layout` (theme) and
 * lays out the tool windows from it with size-based `SplitPane`s (`fixedSize = toolWindow.size.dp`,
 * `onFixedSizeChange = { layout.resizeToolWindow(id, it.value) }`; PROJECT is the FIRST pane, OUTLINE and PROBLEMS the
 * SECOND). Renders [state]'s sub-states (each feature's view from its `view` package) and calls its non-suspending
 * intents and the sub-states' public UI intents; contains no business logic and launches no coroutines for intents.
 * The pending dialog is modal for the pointer and the keyboard ([ModalDialogLayer]).
 */
@Composable
public fun Workbench(state: WorkbenchState, modifier: Modifier = Modifier) {
    val layout by state.layout.state.collectAsState()
    val dark = layout.theme.isDark(isSystemInDarkTheme())
    KarpfenTheme(darkTheme = dark) {
        CompositionLocalProvider(LocalEditorTheme provides if (dark) darkEditorTheme() else lightEditorTheme()) {
            val workspace by state.workspace.collectAsState()
            val dialog by state.dialogs.current.collectAsState()
            ModalDialogLayer(dialog, state.dialogs::respond, modifier.fillMaxSize().background(KarpfenTheme.colors.window)) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (workspace == null) {
                            val settings by state.settings.collectAsState()
                            WelcomeView(
                                recentFolders = settings.recentFolders,
                                onOpenFolder = { state.chooseAndOpenFolder() },
                                onOpenRecent = { state.openFolder(Path.of(it)) },
                            )
                        } else {
                            WorkbenchLayout(
                                layout = layout,
                                layoutState = state.layout,
                                project = { ProjectPane(state) },
                                editor = { EditorPane(state) },
                                outline = { OutlinePane(state) },
                                problems = { ProblemsPane(state) },
                            )
                        }
                    }
                    val status by state.statusBar.state.collectAsState()
                    WorkbenchStatusBar(status)
                }
            }
        }
    }
}

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
            menu = tree.menu,
            onToggle = tree::toggle,
            onSelect = tree::select,
            onOpen = { path -> state.openFile(path) },
            onNewFile = { directory, kind -> state.createFile(directory, kind) },
            onRename = { path -> state.rename(path) },
            onDelete = { path -> state.delete(path) },
            onRevealInFileManager = { path -> state.revealInFileManager(path) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun EditorPane(state: WorkbenchState) {
    val tabs by state.tabs.tabs.collectAsState()
    val active by state.tabs.active.collectAsState()
    val activeEditor by state.tabs.activeEditor.collectAsState()
    EditorArea(
        tabs = tabs,
        activePath = active,
        activeEditor = activeEditor,
        onActivate = state.tabs::activate,
        onClose = { path -> state.closeTab(path) },
        onResolveConflict = { path, resolution -> state.resolveConflict(path, resolution) },
    )
}

@Composable
private fun OutlinePane(state: WorkbenchState) {
    val outline by state.outline.state.collectAsState()
    OutlineView(outline, onNavigate = { state.navigateTo(it) })
}

@Composable
private fun ProblemsPane(state: WorkbenchState) {
    val problems by state.problems.state.collectAsState()
    ProblemsView(problems, onFilterChange = state.problems::setFilter, onNavigate = { state.navigateTo(it) })
}
