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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.core.lang.kmodel.KmodelObjectWriter
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.ui.diagram.DiagramCellEditor
import io.karpfen.ide.ui.diagram.DiagramPane
import io.karpfen.ide.ui.diagram.SharedDiagrams
import io.karpfen.ide.ui.editor.popup.EditorPopups
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.view.CodeEditor
import io.karpfen.ide.ui.foundation.layout.SplitOrientation
import io.karpfen.ide.ui.foundation.layout.SplitPane
import io.karpfen.ide.ui.foundation.layout.SplitSide
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.foundation.widgets.TabStrip
import io.karpfen.ide.ui.workbench.settings.ZoomStep
import io.karpfen.ide.ui.workbench.runtime.DiagramRun
import io.karpfen.ide.ui.workbench.runtime.LiveInstance
import io.karpfen.ide.ui.workbench.statusbar.view.EditorZoomBar
import io.karpfen.ide.ui.workbench.tabs.ConflictBanner
import io.karpfen.ide.ui.workbench.tabs.EditorPaneState
import io.karpfen.ide.ui.workbench.tabs.EditorViewMode
import io.karpfen.ide.ui.workbench.tabs.SplitSide as PaneSplitSide
import io.karpfen.ide.ui.workbench.validation.ValidateButtonUiState
import java.nio.file.Path

/**
 * The editor area of one editor pane, [pane]. It shows the `TabStrip` of the pane's tabs, whose items come from
 * [tabItemOf], which marks tabs in conflict. The strip of the active pane, [isActivePane], underlines its selected tab
 * in the accent colour and the strips of the other panes in a muted colour, so the active pane is told apart at a
 * glance. At the right end of the strip the four buttons of [PaneSplitButtons] split the pane through [onSplit]. Below
 * the strip it shows the conflict banner of the pane's active tab, rendered by [ConflictBanner] from
 * `EditorTab.conflict`. For a modified file the banner offers Reload and Keep mine, and for a deleted file Close and
 * Keep mine. Each button reports a `ConflictResolution`, and both Reload and Close report RELOAD. The `CodeEditor` of
 * [editor], the pane's editor of its active tab, with the editor popups fills the rest. Below an editor a bar holds the
 * zoom controls for the font size [editorFontSize] of the editors, and their clicks go to [onZoomEditor].
 *
 * A press of a pointer button anywhere in the area calls [onActivatePane] before the press reaches the tab strip, the
 * editor or the diagram, and consumes nothing. So a click makes the pane active also where nothing takes the keyboard
 * focus, such as in a diagram.
 *
 * The keyboard focus follows the active pane. When the pane becomes the active one while the focus is outside it, it
 * takes the focus: its editor does if the pane shows the text of its active file, and otherwise an empty focus target
 * of the area does, which holds the focus for the pane and handles no key. So typing, the editor's own shortcuts and
 * the commands of the Edit menu never act in a pane that is not the active one, after a press into a diagram, a focus
 * move with F6 or the close of the neighbouring pane alike. When the editor shows while the empty target holds the
 * focus, as after a switch of the view mode from the diagram alone, the editor takes the focus over. The empty target
 * sits beside the editor and does not wrap it, so a dialog that gives the focus back gives it to the editor. A pane
 * that is active from its first composition takes no focus this way.
 *
 * A file with a diagram, which is a state machine, a metamodel or a model, can be shown in three ways, as the pane's
 * `EditorPaneState.viewMode` says: its text alone, its text and its diagram next to each other, or the diagram alone.
 * The buttons for that sit at the left of the bar below the editor and report to [onViewMode]. After them, and below
 * every editor, the button Validate validates the whole project through [onValidate]. [validation] says whether it is
 * enabled and whether a validation runs. A click on a box of the diagram calls [onSelectInText] with the place of its
 * name in the text. The diagram can be arranged by hand, which is stored in a companion file next to the file. The
 * diagram reads that file again whenever [diskRevision] changes, and [onDiagramSaved] is called after it wrote the
 * file. The diagram derived from the text and its layout by hand are held by [diagrams] for every pane of the window,
 * so two panes that show the diagram of one file always show the same arrangement, while the zoom and the position of
 * the diagram belong to each pane. While a model runs, [run] holds what the run adds to the diagram of the pane's active
 * file, such as the active states of one instance. While the diagram shows and the run uses the file more than once,
 * or the file is a fragment, the bar names the uses after the Validate button, each by its instance and, for a
 * fragment, by the container state that uses it. It marks the use shown and reports a click on another to
 * [onShowInstance]. While the debugger holds the shown use at a breakpoint, the others take no clicks. An opened code
 * cell of the diagram holds [cellEditor]. The context menu of a statechart offers the constructs of the optional
 * language features in [languageFeatures] only, the features switched on for the project. The context menu of the
 * object diagram of a model offers to add a whole object of each type of [modelTypes], the types of the domain
 * metamodel of the modeling context of the pane's active file.
 *
 * Opening the page of a container state calls [onOpenPage] with the name of its fragment. The diagram of a fragment
 * has a bar above it that names [pageUsers], the files whose container states use it, and a click on one calls
 * [onOpenUser]. [pageUsers] is null for a file that is no fragment, which has no such bar.
 *
 * The editor and its popups are drawn at the density of the window, not at the zoomed density of the workbench around
 * them. The size of the editor's text therefore depends only on its font size. The tab strip, the banner and the zoom
 * bar belong to the workbench and follow its zoom.
 *
 * The editor is composed inside `key(activePath)`. So switching tabs disposes the composition of the old editor and
 * its popups, the text fields of the popups included. State that must survive a tab switch, such as the replacement
 * text of find and replace, therefore lives in the editor's models and not in the composables.
 *
 * The area carries the test tag `EditorAreaTestTags.pane` of its pane. The tags of its parts, such as the editor and
 * the diagram, are the same in every pane, so a test with several panes finds them below the tag of the pane.
 *
 * The mediator collects the navigation requests of the editors. They are not relayed through this view. The callbacks
 * call the non-suspending `WorkbenchState` intents directly, and no coroutine is launched here.
 *
 * @param onClose closes a tab of the pane through `WorkbenchState.closeTab`, which runs the unsaved-changes flow.
 */
@Composable
internal fun EditorArea(
    pane: EditorPaneState,
    editor: CodeEditorState?,
    isActivePane: Boolean,
    onActivatePane: () -> Unit,
    onActivate: (Path) -> Unit,
    onClose: (Path) -> Unit,
    onSplit: (PaneSplitSide) -> Unit,
    onResolveConflict: (Path, ConflictResolution) -> Unit,
    editorFontSize: Float,
    onZoomEditor: (ZoomStep) -> Unit,
    onViewMode: (EditorViewMode) -> Unit,
    onSelectInText: (TextRange) -> Unit,
    diskRevision: Long,
    onDiagramSaved: () -> Unit,
    diagrams: SharedDiagrams,
    modifier: Modifier = Modifier,
    run: DiagramRun? = null,
    onShowInstance: (LiveInstance) -> Unit = {},
    cellEditor: DiagramCellEditor? = null,
    languageFeatures: Set<KstatesFeature> = emptySet(),
    modelTypes: KmodelObjectWriter = KmodelObjectWriter.NONE,
    validation: ValidateButtonUiState = ValidateButtonUiState(),
    onValidate: () -> Unit = {},
    onOpenPage: (String) -> Unit = {},
    pageUsers: List<Path>? = null,
    onOpenUser: (Path) -> Unit = {},
) {
    val colors = KarpfenTheme.colors
    val popups = remember { EditorPopups() }
    val tabs = pane.tabs
    val activePath = pane.active
    val activeEditor = editor
    val byId = tabs.associateBy { it.path.toString() }
    val activeTab = tabs.firstOrNull { it.path == activePath }
    val diagramKind = activeTab?.let { EditorViewMode.diagramOf(it.kind) }
    val hasDiagram = diagramKind != null
    val mode = if (hasDiagram) pane.viewMode else EditorViewMode.TEXT
    val focus = remember { PaneFocus(isActivePane) }
    val shownEditor by rememberUpdatedState(activeEditor?.takeIf { activePath != null && mode != EditorViewMode.DIAGRAM })
    LaunchedEffect(isActivePane) {
        if (focus.gained(isActivePane)) shownEditor?.requestFocus() ?: focus.requester.requestFocus()
    }
    LaunchedEffect(shownEditor) {
        if (focus.holding) shownEditor?.requestFocus()
    }
    Column(
        modifier
            .fillMaxSize()
            .background(colors.window)
            .activatesOnPress(onActivatePane)
            .onFocusChanged { focus.inside = it.hasFocus }
            .testTag(EditorAreaTestTags.pane(pane.id.value)),
    ) {
        Box(Modifier.size(0.dp).focusRequester(focus.requester).onFocusChanged { focus.holding = it.isFocused }.focusable())
        if (tabs.isNotEmpty()) {
            TabStrip(
                tabs = tabs.map(::tabItemOf),
                selectedId = activePath?.toString(),
                onSelect = { id -> byId[id]?.let { onActivate(it.path) } },
                onClose = { id -> byId[id]?.let { onClose(it.path) } },
                emphasized = isActivePane,
                trailing = { PaneSplitButtons(enabled = activePath != null, onSplit = onSplit) },
            )
        }
        activeTab?.let(ConflictBanner::of)?.let { banner -> ConflictBannerView(banner) { onResolveConflict(activeTab.path, it) } }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (activeEditor != null && activePath != null) {
                val text: @Composable () -> Unit = {
                    CompositionLocalProvider(LocalDensity provides (LocalWindowDensity.current ?: LocalDensity.current)) {
                        key(activePath) {
                            CodeEditor(
                                state = activeEditor,
                                modifier = Modifier.fillMaxSize().testTag(EditorAreaTestTags.EDITOR),
                                overlays = popups,
                            )
                        }
                    }
                }
                val diagram: @Composable () -> Unit = {
                    if (diagramKind != null) {
                        key(activePath) {
                            Column(Modifier.fillMaxSize()) {
                                pageUsers?.let { users -> PageUsersBar(users, onOpenUser) }
                                DiagramPane(
                                    session = activeEditor.session,
                                    modelFile = activePath,
                                    kind = diagramKind,
                                    modifier = Modifier.weight(1f).fillMaxWidth().testTag(EditorAreaTestTags.DIAGRAM),
                                    reloadKey = diskRevision,
                                    live = run?.live,
                                    cellEditor = cellEditor,
                                    languageFeatures = languageFeatures,
                                    modelTypes = modelTypes,
                                    onSelect = onSelectInText,
                                    onLayoutSaved = onDiagramSaved,
                                    onFollow = onOpenPage,
                                    shared = diagrams,
                                )
                            }
                        }
                    }
                }
                when (mode) {
                    EditorViewMode.TEXT -> text()
                    EditorViewMode.DIAGRAM -> diagram()
                    EditorViewMode.SPLIT -> TextAndDiagram(text, diagram)
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
        if (activeEditor != null && activePath != null) {
            Box(Modifier.fillMaxWidth()) {
                EditorZoomBar(editorFontSize, onZoomEditor, EditorAreaTestTags.ZOOM_BAR)
                Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                    if (hasDiagram) ViewModeSwitch(mode, onViewMode)
                    ValidateButton(validation, onValidate)
                    if (mode != EditorViewMode.TEXT && run != null && run.offersChoice) InstanceSwitch(run, onShowInstance)
                }
            }
        }
    }
}

/**
 * The [text] on the left and the [diagram] on the right, each with half of the width at first. The divider between
 * them can be dragged. The diagram keeps its share of the width and not its width in dp, so when the pane is resized,
 * as by a split of the pane or a drag of the border to a neighbouring pane, text and diagram shrink and grow together.
 */
@Composable
private fun TextAndDiagram(text: @Composable () -> Unit, diagram: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        var diagramShare by remember { mutableStateOf(0.5f) }
        val width = maxWidth
        SplitPane(
            orientation = SplitOrientation.HORIZONTAL,
            fixedSide = SplitSide.SECOND,
            fixedSize = width * diagramShare,
            onFixedSizeChange = { if (width.value > 0f) diagramShare = (it / width).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxSize(),
            first = text,
            second = diagram,
        )
    }
}

/**
 * The three buttons that choose how a file with a diagram is shown. The button of [mode] is marked. A click reports
 * the chosen mode to [onViewMode]. The buttons never take the keyboard focus, so the editor keeps it.
 */
@Composable
private fun ViewModeSwitch(mode: EditorViewMode, onViewMode: (EditorViewMode) -> Unit, modifier: Modifier = Modifier) {
    val colors = KarpfenTheme.colors
    Row(modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        EditorViewMode.entries.forEach { each ->
            Text(
                each.label,
                color = colors.text,
                style = KarpfenTheme.typography.uiSmall,
                modifier = Modifier
                    .focusProperties { canFocus = false }
                    .background(if (each == mode) colors.selection else Color.Transparent)
                    .clickable { onViewMode(each) }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .testTag(EditorAreaTestTags.viewMode(each.label)),
            )
        }
    }
}

/**
 * The uses of [run] as buttons, each labelled with the key of its object, the name of its definition and, in the
 * diagram of a fragment, the container state that uses it. The button of the use shown is marked. A click on another
 * reports it to [onShowInstance], unless the debugger holds the shown use at a breakpoint. The buttons never take the
 * keyboard focus.
 */
@Composable
private fun InstanceSwitch(run: DiagramRun, onShowInstance: (LiveInstance) -> Unit) {
    val colors = KarpfenTheme.colors
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Live:", color = colors.textMuted, style = KarpfenTheme.typography.uiSmall, modifier = Modifier.padding(horizontal = 4.dp))
        run.instances.forEach { instance ->
            val shown = instance == run.shown
            Text(
                instance.label,
                color = if (shown || !run.fixed) colors.text else colors.textMuted,
                style = KarpfenTheme.typography.uiSmall,
                modifier = Modifier
                    .focusProperties { canFocus = false }
                    .background(if (shown) colors.selection else Color.Transparent)
                    .clickable(enabled = !run.fixed && !shown) { onShowInstance(instance) }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .testTag(EditorAreaTestTags.liveInstance(instance.key, instance.page)),
            )
        }
    }
}

/**
 * The bar above the diagram of a fragment. It names the files in [users], whose container states use the fragment,
 * each as a link that reports its path to [onOpenUser], or says that no file uses the fragment. The links never take
 * the keyboard focus.
 */
@Composable
private fun PageUsersBar(users: List<Path>, onOpenUser: (Path) -> Unit) {
    val colors = KarpfenTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.panel).padding(horizontal = 4.dp).testTag(EditorAreaTestTags.PAGE_USERS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val caption = if (users.isEmpty()) "No state machine uses this fragment." else "Used by:"
        Text(caption, color = colors.textMuted, style = KarpfenTheme.typography.uiSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
        users.forEach { user ->
            val name = user.fileName.toString()
            Text(
                name,
                color = colors.accent,
                style = KarpfenTheme.typography.uiSmall,
                modifier = Modifier
                    .focusProperties { canFocus = false }
                    .clickable { onOpenUser(user) }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag(EditorAreaTestTags.pageUser(name)),
            )
        }
    }
}

/**
 * The button that validates the whole project, after the view mode buttons. It shows the [validation]'s label, which
 * says while a validation runs, and it takes clicks only while the validation is enabled. A click calls [onValidate].
 * The button never takes the keyboard focus, so the editor keeps it.
 */
@Composable
private fun ValidateButton(validation: ValidateButtonUiState, onValidate: () -> Unit) {
    val colors = KarpfenTheme.colors
    Text(
        validation.label,
        color = if (validation.enabled) colors.text else colors.textMuted,
        style = KarpfenTheme.typography.uiSmall,
        modifier = Modifier
            .focusProperties { canFocus = false }
            .clickable(enabled = validation.enabled) { onValidate() }
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag(EditorAreaTestTags.VALIDATE),
    )
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

/**
 * Where the keyboard focus of one editor pane goes when the pane becomes the active one.
 *
 * @param wasActive whether the pane is active when it is first composed. A pane that is active from its start, such
 *   as the pane a split makes, takes no focus here, so it never takes the focus from a tool window when a folder opens
 *   and leaves the focus to its editor after a split.
 */
private class PaneFocus(private var wasActive: Boolean) {

    /** Requests the focus for the empty focus target of the pane, which then holds it in place of an editor. */
    val requester = FocusRequester()

    /** True while the pane or a part of it, such as its editor, has the keyboard focus. */
    var inside = false

    /**
     * True while the empty focus target holds the focus for the pane. When the pane's editor shows again, as after a
     * switch of the view mode from the diagram alone, the editor takes the focus over.
     */
    var holding = false

    /**
     * Whether the pane must take the focus now that its activity is [active]: true if it has just become active while
     * the focus is outside it. Remembers [active] for the next call.
     */
    fun gained(active: Boolean): Boolean {
        val gained = active && !wasActive && !inside
        wasActive = active
        return gained
    }
}

/**
 * Calls [onPress] whenever a pointer button is pressed inside this element. The press is seen in the initial pass,
 * before any child, and is not consumed, so the child that was pressed handles it as before.
 */
@Composable
private fun Modifier.activatesOnPress(onPress: () -> Unit): Modifier {
    val current by rememberUpdatedState(onPress)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) current()
            }
        }
    }
}
