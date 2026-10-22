package io.karpfen.ide.ui.editor.popup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.navigation.GoToLineQuery
import io.karpfen.ide.ui.editor.theme.EditorTheme

/**
 * The go-to-line popup (Cmd+L on macOS, Ctrl+G elsewhere), at the top of the text area: accepts `line[:col]` (parsed
 * with [GoToLineQuery.parse]; invalid input disables Go and Enter), shows the current position and line count as a
 * hint. It takes focus when shown; Enter or Go reports the query ([onGo]), Escape cancels ([onCancel]), and focus
 * moving to another component dismisses it ([onDismiss]).
 *
 * @param currentLine the primary caret's one-based line.
 * @param lineCount the number of lines of the text, for the hint.
 */
@Composable
internal fun GoToLinePopup(
    currentLine: Int,
    lineCount: Int,
    theme: EditorTheme,
    textStyle: TextStyle,
    onGo: (GoToLineQuery) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(TextFieldValue()) }
    val query = GoToLineQuery.parse(input.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    Box(Modifier.fillMaxSize().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        PopupSurface(
            theme,
            Modifier
                .width(320.dp)
                .onFocusLost(onDismiss)
                .onPopupKey(PopupKey.CONFIRM) { query?.let(onGo) }
                .onPopupKey(PopupKey.CANCEL, onCancel)
                .testTag(EditorPopupTags.GO_TO_LINE),
        ) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("Go to Line", style = popupLabelStyle(theme).merge(TextStyle(fontWeight = FontWeight.Bold)))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PopupTextField(
                        value = input,
                        onValueChange = { input = it },
                        textStyle = textStyle,
                        theme = theme,
                        placeholder = "line[:column]",
                        modifier = Modifier.weight(1f).focusRequester(focus).testTag(EditorPopupTags.GO_TO_LINE_FIELD),
                    )
                    BarButton("Go", "Go to Line", theme, enabled = query != null) { query?.let(onGo) }
                }
                BasicText(
                    text = GoToLineHint.text(currentLine, lineCount),
                    style = popupLabelStyle(theme).merge(TextStyle(color = theme.foreground.copy(alpha = PopupStyle.MUTED_ALPHA))),
                    modifier = Modifier.testTag(EditorPopupTags.GO_TO_LINE_HINT),
                )
            }
        }
    }
}

/** The go-to-line popup's texts (pure). */
internal object GoToLineHint {

    /** The hint: the current one-based line and the line count. */
    fun text(currentLine: Int, lineCount: Int): String = "Current line $currentLine of $lineCount"

    /** The one-based line of [offset] in [snapshot] (clamped into the text, since carets may lag the text by a dispatch). */
    fun currentLine(snapshot: TextSnapshot, offset: Int): Int = snapshot.positionOf(offset.coerceIn(0, snapshot.length)).line + 1
}
