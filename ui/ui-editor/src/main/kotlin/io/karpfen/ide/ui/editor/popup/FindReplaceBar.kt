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
 * The find/replace bar: query field with toggles Aa (case), W (whole word), .* (regex), an "n/m" counter,
 * an error hint for invalid regexes, Enter/Shift+Enter for next/previous, and in [FindBarMode.REPLACE] a replace
 * field with Replace / Replace All (Enter in it replaces the current match). Escape anywhere in the bar closes it
 * ([onClose]; the caller returns focus to the editor).
 *
 * Renders [query] (the model's query: field text and toggles), [replacement] (the model's replacement), [state] (the
 * model's matches) and the hoisted [fields] (selection and composition of both fields); [queryFocus] is attached to the
 * query field so the caller can focus it. A changed query text is reported through [onQueryTextChange], a changed
 * replacement through [onReplacementChange], a switched toggle through [onToggle]; [onReplace] and [onReplaceAll]
 * replace by the model's replacement.
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

/** The counter or error text of [status], in the error colour where [FindStatus.isError]. */
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
