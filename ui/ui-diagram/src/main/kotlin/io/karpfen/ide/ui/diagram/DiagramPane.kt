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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.notation.diagram.edit.DiagramChange
import io.karpfen.ide.notation.diagram.layout.DiagramLayout
import io.karpfen.ide.notation.sync.DiagramState
import io.karpfen.ide.notation.sync.DiagramTracker
import io.karpfen.ide.notation.sync.VisFiles
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * The context menu of the diagram changes the model. Every such change is one edit of the text of [session], which
 * the editor can undo like any other. It is only made while the diagram shows the current text.
 */
@Composable
public fun DiagramPane(
    session: DocumentSession,
    modelFile: Path,
    kind: DiagramKind,
    modifier: Modifier = Modifier,
    reloadKey: Any? = null,
    onSelect: (TextRange) -> Unit = {},
    onLayoutSaved: () -> Unit = {},
) {
    when (kind) {
        DiagramKind.STATECHART -> NotationPane(DiagramNotation.STATECHART, session, modelFile, modifier, reloadKey, onSelect, onLayoutSaved)
        DiagramKind.TYPES -> NotationPane(DiagramNotation.TYPES, session, modelFile, modifier, reloadKey, onSelect, onLayoutSaved)
        DiagramKind.OBJECTS -> NotationPane(DiagramNotation.OBJECTS, session, modelFile, modifier, reloadKey, onSelect, onLayoutSaved)
    }
}

/** The pane of a diagram of the kind [notation]. It ties the view to the text of [session] and to the companion file. */
@Composable
private fun <D : Any, L : DiagramLayout> NotationPane(
    notation: DiagramNotation<D, L>,
    session: DocumentSession,
    modelFile: Path,
    modifier: Modifier,
    reloadKey: Any?,
    onSelect: (TextRange) -> Unit,
    onLayoutSaved: () -> Unit,
) {
    val state by produceState<DiagramState<D>>(DiagramState.EMPTY, session, notation) {
        val tracker = DiagramTracker(notation.projection)
        combine(session.text.snapshot, session.parse) { snapshot, outcome -> tracker.update(snapshot, outcome) }.collect { value = it }
    }
    val scope = rememberCoroutineScope()
    val visFile = remember(modelFile) { VisFiles.pathFor(modelFile) }
    val saved by rememberUpdatedState(onLayoutSaved)
    val editing = remember(visFile, notation) {
        DiagramEditing(notation.arrangement) { layout ->
            scope.launch {
                withContext(Dispatchers.IO) { VisFiles.write(visFile, layout) }
                saved()
            }
        }
    }
    LaunchedEffect(visFile, reloadKey) {
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
    DiagramView(notation, state, editing, modifier, onSelect, change)
}

/**
 * The view of the diagram [state] of the kind [notation], arranged through [editing]. It draws the diagram, says so
 * when there is none, and shows the note of a stale diagram over the drawing.
 *
 * A change the user makes to the model through the diagram goes to [onChange] as edits of the text the diagram was
 * derived from. It answers whether the text took the change.
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
) {
    val colors = KarpfenTheme.colors
    val diagram = state.diagram
    val faults = remember(notation, diagram) { DiagramFaults() }
    Box(modifier.fillMaxSize().background(colors.window).testTag(DiagramViewTestTags.VIEW)) {
        val empty = diagram == null || faults.guard("looking at the diagram", { true }) { notation.arrangement.isEmpty(diagram) }
        if (diagram == null || empty) {
            Text(
                notation.nothingToDraw,
                color = colors.textMuted,
                style = KarpfenTheme.typography.uiSmall,
                modifier = Modifier.align(Alignment.Center).padding(16.dp).testTag(DiagramViewTestTags.EMPTY),
            )
        } else {
            DiagramCanvas(notation, diagram, editing, faults, state.source?.text, onChange, onSelect)
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

/** What the view says while the text has syntax errors. */
private const val STALE = "The text has errors. This is the diagram of the last text that could be read."
