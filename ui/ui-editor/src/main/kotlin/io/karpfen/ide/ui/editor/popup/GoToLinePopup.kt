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
 * The go-to-line popup at the top of the text area. It opens with Cmd+L on macOS and Ctrl+G elsewhere. It accepts
 * `line` or `line:col`, parsed with [GoToLineQuery.parse]. Invalid input disables the Go button and Enter. A hint shows
 * the current line and the line count.
 *
 * The popup takes focus when it is shown. Enter or Go reports the query to [onGo], and Escape calls [onCancel]. When
 * focus moves to another component, the popup is dismissed through [onDismiss].
 *
 * @param currentLine the one-based line of the primary caret.
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

/** The texts of the go-to-line popup. The object holds pure functions. */
internal object GoToLineHint {

    /** Returns the hint, which shows the current one-based line and the line count. */
    fun text(currentLine: Int, lineCount: Int): String = "Current line $currentLine of $lineCount"

    /**
     * Returns the one-based line of [offset] in [snapshot]. The offset is clamped into the text, because carets may lag
     * behind the text by one dispatch.
     */
    fun currentLine(snapshot: TextSnapshot, offset: Int): Int = snapshot.positionOf(offset.coerceIn(0, snapshot.length)).line + 1
}
