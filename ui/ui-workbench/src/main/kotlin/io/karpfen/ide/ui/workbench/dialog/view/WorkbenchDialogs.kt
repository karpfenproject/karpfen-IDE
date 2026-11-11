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
package io.karpfen.ide.ui.workbench.dialog.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.dialog.DialogButton
import io.karpfen.ide.ui.workbench.dialog.DialogFocusOrder
import io.karpfen.ide.ui.workbench.dialog.DialogPresentation
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse

/**
 * Renders the pending [request] as a modal dialog and reports the answer through [onRespond]. The dialog offers exactly
 * the answers the request accepts, as laid out by [DialogPresentation]. It is drawn over the workbench on a scrim that
 * swallows all pointer input. Enter presses the default button and Escape dismisses the dialog.
 *
 * The rename and new file dialogs have a text field. It starts focused, with its whole text selected. The dialog that
 * picks the descriptor to run has a list instead. It starts with the preselected entry selected. Up and Down move the
 * selection, and a click selects an entry. Tab and Shift+Tab cycle through the focus stops of [DialogFocusOrder] and
 * never leave the dialog. [ModalDialogLayer] keeps the focus out of the workbench behind it.
 */
@Composable
internal fun WorkbenchDialogs(request: DialogRequest<*>?, onRespond: (DialogResponse) -> Unit) {
    if (request == null) return
    val presentation = remember(request) { DialogPresentation.of(request) }
    var text by remember(request) {
        val initial = presentation.input.orEmpty()
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    var selected by remember(request) { mutableStateOf(presentation.preselected) }
    val stops = remember(request) { List(presentation.buttons.size + 1) { FocusRequester() } }
    val order = DialogFocusOrder.of(presentation, text.text)
    fun Modifier.focusStop(stop: Int): Modifier = focusRequester(stops[stop]).focusProperties {
        next = stops[order.next(stop)]
        previous = stops[order.previous(stop)]
    }
    fun press(button: DialogButton) {
        presentation.responseFor(button.choice, text.text, selected)?.let(onRespond)
    }
    val choices = presentation.choices
    val colors = KarpfenTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .pointerInput(request) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(min = 320.dp, max = 520.dp)
                .background(colors.panel, RoundedCornerShape(6.dp))
                .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                .padding(16.dp)
                .testTag(WorkbenchDialogTestTags.DIALOG)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Escape -> onRespond(presentation.dismiss).let { true }
                        event.key == Key.Enter -> presentation.buttons.firstOrNull { it.isDefault }?.let(::press).let { true }
                        choices != null && event.key == Key.DirectionDown -> {
                            selected = (selected + 1).coerceAtMost(choices.lastIndex)
                            true
                        }
                        choices != null && event.key == Key.DirectionUp -> {
                            selected = (selected - 1).coerceAtLeast(0)
                            true
                        }
                        else -> false
                    }
                }
                .then(if (presentation.input == null) Modifier.focusStop(0).focusable() else Modifier),
        ) {
            Text(presentation.title, color = colors.text, style = KarpfenTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(8.dp))
            Text(presentation.message, color = colors.text, style = KarpfenTheme.typography.ui)
            if (choices != null) {
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().border(1.dp, colors.border)) {
                    choices.forEachIndexed { index, choice ->
                        Text(
                            choice,
                            color = colors.text,
                            style = KarpfenTheme.typography.ui,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (index == selected) colors.selection else Color.Transparent)
                                .clickable { selected = index }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag(WorkbenchDialogTestTags.choice(index)),
                        )
                    }
                }
            }
            if (presentation.input != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusStop(0).testTag(WorkbenchDialogTestTags.INPUT),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                presentation.buttons.forEachIndexed { index, button ->
                    val enabled = presentation.responseFor(button.choice, text.text, selected) != null
                    val tagged = Modifier.testTag(WorkbenchDialogTestTags.button(button.label)).focusStop(index + 1)
                    if (button.isDefault) {
                        Button(onClick = { press(button) }, enabled = enabled, modifier = tagged) { Text(button.label) }
                    } else {
                        OutlinedButton(onClick = { press(button) }, enabled = enabled, modifier = tagged) { Text(button.label) }
                    }
                }
            }
        }
    }
    LaunchedEffect(request) { stops.first().requestFocus() }
}
