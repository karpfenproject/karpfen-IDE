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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.karpfen.ide.notation.diagram.edit.DiagramAction
import io.karpfen.ide.notation.diagram.edit.DiagramCell
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import kotlin.math.roundToInt

/** What the canvas of a diagram is waiting for besides the usual gestures. */
internal sealed interface CanvasMode {
    /** Nothing. Clicks select and drags move. */
    data object Idle : CanvasMode

    /**
     * The user is to click a box for [action]. The boxes to choose from are marked, and so are the [picked] ones the
     * user clicked for earlier steps of the same chain of actions.
     */
    class Picking(val action: DiagramAction.Pick, val picked: List<String>) : CanvasMode

    /** The user is to type a text for [action] into a field at [position] of the canvas, in pixels. */
    class Asking(val action: DiagramAction.Ask, val position: Offset) : CanvasMode

    /** The code [cell] is open at [position] of the canvas, in pixels, and the user edits its code. */
    class Editing(val cell: DiagramCell, val position: Offset) : CanvasMode
}

/**
 * The opened code [cell]: a small pane over the canvas near [position], kept inside a view of the size [viewSize].
 * Its head says what the code is and has the buttons Save and Close. Below is the code in a field that takes the
 * keyboard focus. Save, or Enter with Ctrl or Cmd, hands the code to [onSave]. Close or Escape calls [onClose] and
 * drops what was typed.
 */
@Composable
internal fun CodeCellPane(cell: DiagramCell, position: Offset, viewSize: IntSize, onSave: (String) -> Unit, onClose: () -> Unit) {
    val colors = KarpfenTheme.colors
    var value by remember(cell.key) { mutableStateOf(TextFieldValue(cell.code)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(cell.key) { focus.requestFocus() }
    Column(
        Modifier
            .offset {
                IntOffset(
                    position.x.roundToInt().coerceIn(0, maxOf(0, viewSize.width - CELL_WIDTH.dp.roundToPx())),
                    position.y.roundToInt().coerceIn(0, maxOf(0, viewSize.height - CELL_HEIGHT.dp.roundToPx())),
                )
            }
            .size(CELL_WIDTH.dp, CELL_HEIGHT.dp)
            .background(colors.panel)
            .border(1.dp, colors.accent)
            .testTag(DiagramViewTestTags.CELL),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(cell.title, color = colors.textMuted, style = KarpfenTheme.typography.uiSmall, maxLines = 1, modifier = Modifier.weight(1f))
            CellButton("Save", DiagramViewTestTags.CELL_SAVE) { onSave(value.text) }
            CellButton("Close", DiagramViewTestTags.CELL_CLOSE, onClose)
        }
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            textStyle = KarpfenTheme.typography.monospace.copy(color = colors.text),
            cursorBrush = SolidColor(colors.text),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(colors.window)
                .verticalScroll(rememberScrollState())
                .padding(6.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        event.key == Key.Escape -> {
                            onClose()
                            true
                        }
                        (event.key == Key.Enter || event.key == Key.NumPadEnter) && (event.isCtrlPressed || event.isMetaPressed) -> {
                            onSave(value.text)
                            true
                        }
                        else -> false
                    }
                }
                .testTag(DiagramViewTestTags.CELL_CODE),
        )
    }
}

/** A small button in the head of an opened code cell. */
@Composable
private fun CellButton(label: String, tag: String, onClick: () -> Unit) {
    Text(
        label,
        color = KarpfenTheme.colors.accent,
        style = KarpfenTheme.typography.uiSmall,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp).testTag(tag),
    )
}

/** The width of an opened code cell, in density-independent units. */
private const val CELL_WIDTH = 420

/** The height of an opened code cell, in density-independent units. */
private const val CELL_HEIGHT = 200

/**
 * A one-line field at [position] of the canvas that starts with [initial] selected and takes the keyboard focus.
 * Enter hands the text to [onDone]. Escape calls [onCancel].
 */
@Composable
internal fun AskField(initial: String, position: Offset, onDone: (String) -> Unit, onCancel: () -> Unit) {
    val colors = KarpfenTheme.colors
    var value by remember { mutableStateOf(TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        textStyle = KarpfenTheme.typography.ui.copy(color = colors.text),
        cursorBrush = SolidColor(colors.text),
        modifier = Modifier
            .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
            .width(ASK_WIDTH.dp)
            .background(colors.window)
            .border(1.dp, colors.accent)
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                when {
                    event.type != KeyEventType.KeyDown -> false
                    event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                        onDone(value.text)
                        true
                    }
                    event.key == Key.Escape -> {
                        onCancel()
                        true
                    }
                    else -> false
                }
            }
            .testTag(DiagramViewTestTags.ASK),
    )
}

/** The width of the field a user types a name into, in density-independent units. */
private const val ASK_WIDTH = 180
