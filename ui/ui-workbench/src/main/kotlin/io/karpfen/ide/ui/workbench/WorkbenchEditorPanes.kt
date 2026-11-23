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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.kmodel.KmodelObjectWriter
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.analysis.ProjectFragments
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.runtime.session.debug.DebuggerState
import io.karpfen.ide.runtime.session.RuntimeSession
import io.karpfen.ide.runtime.session.observation.ObservedModel
import io.karpfen.ide.ui.diagram.SharedDiagrams
import io.karpfen.ide.ui.diagram.rememberSharedDiagrams
import io.karpfen.ide.ui.workbench.runtime.DiagramLives
import io.karpfen.ide.ui.workbench.runtime.LiveInstance
import io.karpfen.ide.ui.workbench.settings.IdeSettings
import io.karpfen.ide.ui.workbench.state.WorkbenchState
import io.karpfen.ide.ui.workbench.tabs.PaneId
import io.karpfen.ide.ui.workbench.tabs.view.CodeCellEditor
import io.karpfen.ide.ui.workbench.tabs.view.EditorArea
import io.karpfen.ide.ui.workbench.tabs.view.EditorPaneTree
import io.karpfen.ide.ui.workbench.validation.ValidateButtonUiState
import java.nio.file.Path

/**
 * The editor area with [state]'s panes. It draws the tree of `EditorTabsState.layout` through [EditorPaneTree], and a
 * drag of a border between two panes resizes their split through `EditorTabsState.resize`. Every pane is an
 * [EditorPaneView].
 *
 * What all panes need is collected here once and handed to each pane as a [PaneSurroundings]: the workspace with its
 * analysis and the revision of the files on disk, the settings, the validation, the live run with its observed model
 * and its debugger, and the language services. The panes share one [SharedDiagrams], so two panes that show the
 * diagram of the same file show the same diagram and the same arrangement.
 */
@Composable
internal fun EditorPanes(state: WorkbenchState) {
    val layout by state.tabs.layout.collectAsState()
    val workspace by state.workspace.collectAsState()
    val diskRevision = workspace?.scanRevision?.collectAsState()?.value ?: 0L
    val settings by state.settings.collectAsState()
    val canValidate by state.validation.canValidate.collectAsState()
    val validating by state.validation.isBusy.collectAsState()
    val running = state.runtime.collectAsState().value?.selected?.collectAsState()?.value?.takeIf { it.isLive }?.session
    val observed = running?.model?.collectAsState()?.value
    val debugger = running?.debugger?.state?.collectAsState()?.value ?: DebuggerState.Unavailable
    val analysis = workspace?.analysis?.collectAsState()?.value
    val services by state.languageServices.collectAsState()
    val diagrams = rememberSharedDiagrams()
    val surroundings = PaneSurroundings(
        workspace = workspace,
        diskRevision = diskRevision,
        settings = settings,
        validation = ValidateButtonUiState(enabled = canValidate, busy = validating),
        live = running?.let { LiveRun(it, observed, debugger) },
        analysis = analysis,
        services = services,
        diagrams = diagrams,
    )
    EditorPaneTree(layout, onResize = state.tabs::resize) { id ->
        EditorPaneView(state, id, isActive = layout.activePane == id, surroundings = surroundings)
    }
}

/**
 * The editor area of the pane [id] of [state], drawn by [EditorArea]. Every callback acts on this pane and its active
 * file, not on the active pane: the tab strip activates and closes the pane's tabs, the split buttons split this pane,
 * the view mode is this pane's, a click into the diagram selects the place in the text of this pane, the page of a
 * container state and the files that use a fragment open in this pane, and the code cell editor is an editor of this
 * pane. The intents that act on the active pane, such as `WorkbenchState.navigateTo` and `WorkbenchState.openPage`, are
 * called after the pane was made active, which a press into the pane does as well.
 *
 * The instance whose states the diagram shows while a model runs is chosen per pane and per file. The choice lives in
 * the composition of the pane and is forgotten when the run changes.
 */
@Composable
private fun EditorPaneView(state: WorkbenchState, id: PaneId, isActive: Boolean, surroundings: PaneSurroundings) {
    val panes by state.tabs.panes.collectAsState()
    val pane = panes[id] ?: return
    val active = pane.active
    val tabs = state.tabs
    val editor = active?.let { tabs.editorFor(id, it) }
    val live = surroundings.live
    val chosenInstances = remember(live?.session) { mutableStateMapOf<Path, LiveInstance>() }
    val chosen = active?.let { chosenInstances[it] }
    val run = remember(live, active, chosen) {
        val observed = live?.observed
        if (live == null || observed == null || active == null) null else DiagramLives.of(observed.states, active, live.debugger, chosen)
    }
    val pages = remember(surroundings.analysis) { surroundings.analysis?.let(::ProjectFragments) }
    val pageUsers = remember(pages, active) { pages?.all()?.firstOrNull { it.path == active }?.let { pages.usersOf(it.name) } }
    val modelTypes = remember(surroundings.analysis, active) {
        val file = active?.takeIf { FileKind.of(it.fileName?.toString().orEmpty()) == FileKind.KMODEL }
        val analysis = surroundings.analysis
        if (analysis == null || file == null) KmodelObjectWriter.NONE else KmodelObjectWriter.of(analysis, file)
    }
    val cellEditor = remember(editor, surroundings.services, surroundings.workspace) {
        val known = surroundings.services
        if (editor == null || known == null) null else CodeCellEditor(editor, known, surroundings.workspace)
    }
    val inPane: (() -> Unit) -> Unit = { action ->
        tabs.activatePane(id)
        action()
    }
    EditorArea(
        pane = pane,
        editor = editor,
        isActivePane = isActive,
        onActivatePane = { tabs.activatePane(id) },
        onActivate = { path -> tabs.activate(id, path) },
        onClose = { path -> state.closeTab(id, path) },
        onSplit = { side -> state.splitPane(id, side) },
        onResolveConflict = { path, resolution -> state.resolveConflict(path, resolution) },
        editorFontSize = surroundings.settings.editorFontSize,
        onZoomEditor = { state.zoomEditor(it) },
        onViewMode = { mode -> active?.let { tabs.setViewMode(id, it, mode) } },
        onSelectInText = { range -> active?.let { inPane { state.navigateTo(Location(it, range)) } } },
        diskRevision = surroundings.diskRevision,
        onDiagramSaved = { state.refreshFiles() },
        diagrams = surroundings.diagrams,
        run = run,
        onShowInstance = { use -> active?.let { chosenInstances[it] = use } },
        cellEditor = cellEditor,
        languageFeatures = surroundings.settings.languageFeatures,
        modelTypes = modelTypes,
        validation = surroundings.validation,
        onValidate = { state.validation.validate() },
        onOpenPage = { name -> active?.let { inPane { state.openPage(it, name) } } },
        pageUsers = pageUsers,
        onOpenUser = { user -> active?.let { inPane { state.openWithDiagram(user, it) } } },
    )
}

/**
 * What every editor pane draws with, collected once for all panes.
 *
 * @property workspace the open workspace, or null.
 * @property diskRevision the revision of the files on disk, which makes the diagrams read their companion files again.
 * @property settings the settings of the IDE and the project.
 * @property validation the state of the button that validates the project.
 * @property live the run whose model is observed while it is live, or null.
 * @property analysis the latest analysis of the project, or null.
 * @property services the language services of the workspace, or null.
 * @property diagrams the diagrams the panes share.
 */
private class PaneSurroundings(
    val workspace: Workspace?,
    val diskRevision: Long,
    val settings: IdeSettings,
    val validation: ValidateButtonUiState,
    val live: LiveRun?,
    val analysis: ProjectAnalysis?,
    val services: LanguageServices?,
    val diagrams: SharedDiagrams,
)

/**
 * A live run as the diagrams see it.
 *
 * @property session the session of the run, which identifies it.
 * @property observed the model the run observes, or null before the first observation.
 * @property debugger the state of the run's debugger.
 */
private data class LiveRun(val session: RuntimeSession, val observed: ObservedModel?, val debugger: DebuggerState)
