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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.editor.model.find.FindQuery
import io.karpfen.ide.ui.editor.model.find.FindResult
import io.karpfen.ide.ui.editor.state.FindBarMode
import io.karpfen.ide.ui.editor.theme.EditorTheme

/**
 * The find and replace bar. It has a query field with the toggles `Aa` for case, `W` for whole words and `.*` for
 * regular expressions. It shows an "n/m" counter and an error hint for an invalid regular expression. Enter and
 * Shift+Enter run find next and find previous. In [FindBarMode.REPLACE] it adds a replace field with the buttons
 * Replace and Replace All, and Enter in that field replaces the current match. Escape anywhere in the bar closes it
 * through [onClose], and the caller returns focus to the editor.
 *
 * The bar renders [query], which gives the field text and the toggles, [replacement] and [state], which holds the
 * matches. All three come from the model. It also renders the hoisted [fields], which hold the selection and
 * composition of both text fields. [queryFocus] is attached to the query field, so the caller can focus it. A changed
 * query text is reported through [onQueryTextChange], a changed replacement through [onReplacementChange] and a
 * switched toggle through [onToggle]. [onReplace] and [onReplaceAll] replace with the model's replacement.
 */
@Composable
internal fun FindReplaceBar(
    mode: FindBarMode,
    query: FindQuery,
    replacement: String,
    state: FindResult,
    fields: FindBarFields,
    queryFocus: FocusRequester,
    theme: EditorTheme,
    textStyle: TextStyle,
    onQueryTextChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onToggle: (FindOption) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    val hasMatches = state.total > 0
    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.popupBackground)
            .drawBehind { drawLine(theme.popupBorder, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .onPopupKey(PopupKey.CANCEL, onClose)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag(EditorPopupTags.FIND_BAR),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PopupTextField(
                value = fields.queryField(query.text),
                onValueChange = { value -> fields.editQuery(value, query.text)?.let(onQueryTextChange) },
                textStyle = textStyle,
                theme = theme,
                placeholder = "Find",
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(queryFocus)
                    .onPopupKey(PopupKey.CONFIRM, onNext)
                    .onPopupKey(PopupKey.CONFIRM_BACKWARD, onPrevious)
                    .testTag(EditorPopupTags.FIND_QUERY),
            )
            FindOption.entries.forEach { option ->
                BarToggle(option.label, option.description, option.isOn(query), theme) { onToggle(option) }
            }
            FindStatusText(FindStatus.of(state), theme)
            BarButton("↑", "Previous Match", theme, enabled = hasMatches, onClick = onPrevious)
            BarButton("↓", "Next Match", theme, enabled = hasMatches, onClick = onNext)
            BarButton("×", "Close", theme, onClick = onClose)
        }
        if (mode == FindBarMode.REPLACE) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PopupTextField(
                    value = fields.replacementField(replacement),
                    onValueChange = { value -> fields.editReplacement(value, replacement)?.let(onReplacementChange) },
                    textStyle = textStyle,
                    theme = theme,
                    placeholder = "Replace",
                    modifier = Modifier
                        .weight(1f)
                        .onPopupKey(PopupKey.CONFIRM, onReplace)
                        .testTag(EditorPopupTags.REPLACE_FIELD),
                )
                BarButton("Replace", "Replace", theme, enabled = hasMatches, onClick = onReplace)
                BarButton("Replace All", "Replace All", theme, enabled = hasMatches, onClick = onReplaceAll)
            }
        }
    }
}

/** Shows the counter or error text of [status]. It uses the error colour when [FindStatus.isError] is true. */
@Composable
private fun FindStatusText(status: FindStatus, theme: EditorTheme) {
    val color = if (status.isError) theme.error else theme.foreground
    BasicText(
        text = status.text,
        style = popupLabelStyle(theme).merge(TextStyle(color = color)),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(min = 40.dp, max = 240.dp).testTag(EditorPopupTags.FIND_STATUS),
    )
}
