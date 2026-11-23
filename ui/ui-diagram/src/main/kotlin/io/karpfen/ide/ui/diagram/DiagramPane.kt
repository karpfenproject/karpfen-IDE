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
package io.karpfen.ide.ui.diagram

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.lang.kmodel.KmodelObjectWriter
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.notation.diagram.edit.DiagramChange
import io.karpfen.ide.notation.diagram.layout.DiagramLayout
import io.karpfen.ide.notation.sync.DiagramState
import io.karpfen.ide.notation.sync.VisFiles
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * The diagram of the model file [modelFile], whose text [session] holds. [kind] says which diagram that is. It must
 * fit the language of the file. The drawing follows the text: whenever the text or its parse changes, the diagram is
 * derived again from the text. While the text has syntax errors, the last drawing stays and a note says that it is
 * outdated.
 *
 * The drawing can be moved by dragging the empty canvas and by scrolling, and zoomed with the buttons in its corner
 * or by scrolling while Ctrl or Cmd is held. A click on a box calls [onSelect] with the place of its name in the
 * text, so the caller can show it in the editor.
 *
 * The diagram can be arranged by hand. Boxes, the bars of splits and joins and the waypoints of lines are dragged on
 * a grid, and a secondary click on a line or a waypoint opens a context menu that adds or removes a waypoint. The arrangement is stored in the companion file
 * of [modelFile], next to it with `.vis` appended. After every write [onLayoutSaved] is called, so the caller can
 * show the new file. The companion file is read when the pane appears and again whenever [reloadKey] changes, which
 * is how changes made to it by other tools come in. The text always wins: what the companion file says about elements
 * the text does not have is ignored.
 *
 * While a model runs, [live] holds what the run adds to the diagram: a statechart marks the states its machine is
 * in and the place where a debugger paused it. It is null while nothing runs, and for a file the run has nothing to
 * mark in.
 *
 * A code cell that the user opens holds [cellEditor], the editor of the host, or a plain text field without one.
 *
 * The context menu of the diagram changes the model. Every such change is one edit of the text of [session], which
 * the editor can undo like any other. It is only made while the diagram shows the current text. It offers the
 * constructs of an optional language feature of KStates only while that feature is among [languageFeatures]. The
 * object diagram of a model offers to add a whole object of each type of [modelTypes], the types of the domain
 * metamodel of the modeling context of [modelFile], which [modelTypes] also writes. The caller makes it from the latest
 * analysis of the project, since the diagram does not know the project.
 *
 * An entry that leaves the diagram for another one, such as the page of a container state, calls [onFollow] with the
 * name the model refers to. The caller finds the file of that name and opens its diagram.
 *
 * The diagram derived from the text and its layout by hand belong to the file, and [shared] holds them for every pane
 * that shows the diagram of the same file. Panes that are given the same [shared] therefore always show the same
 * arrangement, also while a box is dragged in one of them, and the companion file is written once per change. The
 * zoom, the position, the menus and the code cells belong to each pane. The default shares with no other pane.
 */
@Composable
public fun DiagramPane(
    session: DocumentSession,
    modelFile: Path,
    kind: DiagramKind,
    modifier: Modifier = Modifier,
    reloadKey: Any? = null,
    live: DiagramLive? = null,
    cellEditor: DiagramCellEditor? = null,
    languageFeatures: Set<KstatesFeature> = emptySet(),
    modelTypes: KmodelObjectWriter = KmodelObjectWriter.NONE,
    onSelect: (TextRange) -> Unit = {},
    onLayoutSaved: () -> Unit = {},
    onFollow: (String) -> Unit = {},
    shared: SharedDiagrams = rememberSharedDiagrams(),
) {
    val notation = when (kind) {
        DiagramKind.STATECHART -> DiagramNotation.STATECHART
        DiagramKind.TYPES -> DiagramNotation.TYPES
        DiagramKind.OBJECTS -> DiagramNotation.OBJECTS
    }
    NotationPane(
        notation, shared, session, modelFile, modifier, reloadKey, live, cellEditor, languageFeatures, modelTypes, onSelect, onLayoutSaved,
        onFollow,
    )
}

/**
 * The pane of a diagram of the kind [notation]. It ties the view to the share of the diagram in [shared], which
 * follows the text of [session] and holds the layout of the companion file.
 */
@Composable
private fun <D : Any, L : DiagramLayout> NotationPane(
    notation: DiagramNotation<D, L>,
    shared: SharedDiagrams,
    session: DocumentSession,
    modelFile: Path,
    modifier: Modifier,
    reloadKey: Any?,
    live: DiagramLive?,
    cellEditor: DiagramCellEditor?,
    languageFeatures: Set<KstatesFeature>,
    modelTypes: KmodelObjectWriter,
    onSelect: (TextRange) -> Unit,
    onLayoutSaved: () -> Unit,
    onFollow: (String) -> Unit,
) {
    val visFile = remember(modelFile) { VisFiles.pathFor(modelFile) }
    val lease = remember(shared, session, visFile, notation) { shared.lease(notation, session, modelFile) }
    val share = lease.share
    SideEffect { share.onSaved = onLayoutSaved }
    val state by share.state.collectAsState()
    val editing = share.editing
    LaunchedEffect(editing, reloadKey) {
        editing.loaded(withContext(Dispatchers.IO) { VisFiles.read(visFile) })
    }
    val shown by rememberUpdatedState(state)
    val change: (DiagramChange) -> Boolean = remember(session) {
        { made ->
            val source = shown.source
            source != null && made.edits.isNotEmpty() && session.text.snapshot.value.version == source.version &&
                session.text.apply(made.edits, EditOrigin.Command(DIAGRAM_EDIT)) != null
        }
    }
    DiagramView(
        notation, state, editing, modifier, onSelect, change, live?.active.orEmpty(), live?.pause, cellEditor,
        languageFeatures, modelTypes, onFollow,
    )
}

/**
 * The view of the diagram [state] of the kind [notation], arranged through [editing]. It draws the diagram, says so
 * when there is none, and shows the note of a stale diagram over the drawing. A diagram with nothing to draw, which a
 * text without types, objects or states gives, still has its canvas, so that the menu of the canvas can add the first
 * element, and a muted text in the middle says that there is nothing to draw yet.
 *
 * A change the user makes to the model through the diagram goes to [onChange] as edits of the text the diagram was
 * derived from. It answers whether the text took the change. The context menu offers what the notation's actions
 * offer while [languageFeatures] are switched on and with the types of [modelTypes]. An action that leaves the diagram
 * goes to [onFollow].
 *
 * A failure inside the view does not reach the window. What failed is left out, and a second note over the drawing
 * says what went wrong. Its text can be selected and copied. See [DiagramFaults].
 */
@Composable
internal fun <D : Any, L : DiagramLayout> DiagramView(
    notation: DiagramNotation<D, L>,
    state: DiagramState<D>,
    editing: DiagramEditing<D, L>,
    modifier: Modifier = Modifier,
    onSelect: (TextRange) -> Unit = {},
    onChange: (DiagramChange) -> Boolean = { false },
    active: Set<String> = emptySet(),
    pause: DiagramPause? = null,
    cellEditor: DiagramCellEditor? = null,
    languageFeatures: Set<KstatesFeature> = emptySet(),
    modelTypes: KmodelObjectWriter = KmodelObjectWriter.NONE,
    onFollow: (String) -> Unit = {},
) {
    val colors = KarpfenTheme.colors
    val diagram = state.diagram
    val faults = remember(notation, diagram) { DiagramFaults() }
    Box(modifier.fillMaxSize().background(colors.window).testTag(DiagramViewTestTags.VIEW)) {
        if (diagram == null) {
            Muted(notation.nothingToDraw)
        } else {
            val actions = remember(notation, languageFeatures, modelTypes) { notation.actions(languageFeatures, modelTypes) }
            DiagramCanvas(notation, actions, diagram, editing, faults, state.source?.text, active, pause, cellEditor, onChange, onSelect, onFollow)
            if (faults.guard("looking at the diagram", { false }) { notation.arrangement.isEmpty(diagram) }) Muted(NOTHING_YET)
        }
        val fault = state.problem?.let { "The diagram could not be derived from the text: $it. This is the last diagram that could." } ?: faults.note
        Column(
            Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp, end = BUTTONS_ROOM.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.isStale) Note(STALE, DiagramViewTestTags.STALE)
            if (fault != null) SelectionContainer { Note(fault, DiagramViewTestTags.FAULT) }
        }
    }
}

/** The muted [text] in the middle of the view that says why there is nothing to see. */
@Composable
private fun BoxScope.Muted(text: String) {
    Text(
        text,
        color = KarpfenTheme.colors.textMuted,
        style = KarpfenTheme.typography.uiSmall,
        modifier = Modifier.align(Alignment.Center).padding(16.dp).testTag(DiagramViewTestTags.EMPTY),
    )
}

/** A note over the drawing that says [text]. */
@Composable
private fun Note(text: String, tag: String) {
    val colors = KarpfenTheme.colors
    Text(
        text,
        color = colors.warning,
        style = KarpfenTheme.typography.uiSmall,
        modifier = Modifier.background(colors.panel).padding(horizontal = 8.dp, vertical = 4.dp).testTag(tag),
    )
}

/**
 * The room the notes leave free at the right of the view, in density-independent units. The zoom buttons sit there,
 * and a long note must not cover them.
 */
private const val BUTTONS_ROOM = 170

/** The name of the edits that a diagram makes to the text, as the undo history records them. */
private const val DIAGRAM_EDIT = "diagram"

/**
 * What the view says over the canvas of a diagram that the text gives but that has nothing to draw yet, such as a
 * metamodel without types. The menu of the canvas adds the first element.
 */
internal const val NOTHING_YET: String = "Nothing to draw yet. A right-click on the canvas adds the first element."

/** What the view says while the text has syntax errors. */
private const val STALE = "The text has errors. This is the diagram of the last text that could be read."
