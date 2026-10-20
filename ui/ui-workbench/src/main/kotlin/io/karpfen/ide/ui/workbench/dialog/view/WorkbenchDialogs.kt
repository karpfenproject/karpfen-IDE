package io.karpfen.ide.ui.workbench.dialog.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import io.karpfen.ide.ui.workbench.dialog.DialogPresentation
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse

/**
 * Renders the pending [request] as a modal dialog and reports the answer through [onRespond]; it offers exactly the
 * answers the request accepts (`DialogRequest.accepts`), as decided by [DialogPresentation]. Drawn over the workbench
 * with a scrim that swallows pointer input; Enter presses the default button, Escape dismisses. A text field (rename,
 * new file) starts focused with its text selected.
 */
@Composable
internal fun WorkbenchDialogs(request: DialogRequest<*>?, onRespond: (DialogResponse) -> Unit) {
    if (request == null) return
    val presentation = remember(request) { DialogPresentation.of(request) }
    var text by remember(request) {
        val initial = presentation.input.orEmpty()
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val focus = remember(request) { FocusRequester() }
    fun press(button: DialogButton) {
        presentation.responseFor(button.choice, text.text)?.let(onRespond)
    }
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
                    when (event.key) {
                        Key.Escape -> onRespond(presentation.dismiss).let { true }
                        Key.Enter -> presentation.buttons.firstOrNull { it.isDefault }?.let(::press).let { true }
                        else -> false
                    }
                }
                .then(if (presentation.input == null) Modifier.focusRequester(focus).focusable() else Modifier),
        ) {
            Text(presentation.title, color = colors.text, style = KarpfenTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(8.dp))
            Text(presentation.message, color = colors.text, style = KarpfenTheme.typography.ui)
            if (presentation.input != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(WorkbenchDialogTestTags.INPUT),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                presentation.buttons.forEach { button ->
                    val enabled = presentation.responseFor(button.choice, text.text) != null
                    val tagged = Modifier.testTag(WorkbenchDialogTestTags.button(button.label))
                    if (button.isDefault) {
                        Button(onClick = { press(button) }, enabled = enabled, modifier = tagged) { Text(button.label) }
                    } else {
                        OutlinedButton(onClick = { press(button) }, enabled = enabled, modifier = tagged) { Text(button.label) }
                    }
                }
            }
        }
    }
    LaunchedEffect(request) { focus.requestFocus() }
}
