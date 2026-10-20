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
 * The find/replace bar (spec §7.2): query field with toggles Aa (case), W (whole word), .* (regex), an "n/m" counter,
 * an error hint for invalid regexes, Enter/Shift+Enter for next/previous, and in [FindBarMode.REPLACE] a replace
 * field with Replace / Replace All (Enter in it replaces the current match). Escape anywhere in the bar closes it
 * ([onClose]; the caller returns focus to the editor).
 *
 * Renders [state] (the model's matches) and the hoisted [fields] (the texts and the query the bar shows); [queryFocus]
 * is attached to the query field so the caller can focus it. Field edits and toggles report the query to send through
 * [onQueryChange].
 */
@Composable
internal fun FindReplaceBar(
    mode: FindBarMode,
    state: FindResult,
    fields: FindBarFields,
    queryFocus: FocusRequester,
    theme: EditorTheme,
    textStyle: TextStyle,
    onQueryChange: (FindQuery) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onReplace: (String) -> Unit,
    onReplaceAll: (String) -> Unit,
    onClose: () -> Unit,
) {
    val hasMatches = state.total > 0
    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.popupBackground)
            .drawBehind { drawLine(theme.popupBorder, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .onPopupKey { key -> (key == PopupKey.CANCEL).also { cancel -> if (cancel) onClose() } }
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag(EditorPopupTags.FIND_BAR),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PopupTextField(
                value = fields.queryField,
                onValueChange = { value -> fields.editQuery(value)?.let(onQueryChange) },
                textStyle = textStyle,
                theme = theme,
                placeholder = "Find",
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(queryFocus)
                    .onPopupKey { key ->
                        when (key) {
                            PopupKey.CONFIRM -> onNext()
                            PopupKey.CONFIRM_BACKWARD -> onPrevious()
                            PopupKey.CANCEL -> Unit // handled by the bar (preview phase reaches the bar first)
                        }
                        key != PopupKey.CANCEL
                    }
                    .testTag(EditorPopupTags.FIND_QUERY),
            )
            FindOption.entries.forEach { option ->
                BarToggle(option.label, option.description, option.isOn(fields.query), theme) { onQueryChange(fields.toggle(option)) }
            }
            FindStatusText(FindStatus.of(state), theme)
            BarButton("↑", "Previous Match", theme, enabled = hasMatches, onClick = onPrevious)
            BarButton("↓", "Next Match", theme, enabled = hasMatches, onClick = onNext)
            BarButton("×", "Close", theme, onClick = onClose)
        }
        if (mode == FindBarMode.REPLACE) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PopupTextField(
                    value = fields.replacementField,
                    onValueChange = { fields.replacementField = it },
                    textStyle = textStyle,
                    theme = theme,
                    placeholder = "Replace",
                    modifier = Modifier
                        .weight(1f)
                        .onPopupKey { key -> (key == PopupKey.CONFIRM).also { confirm -> if (confirm) onReplace(fields.replacementField.text) } }
                        .testTag(EditorPopupTags.REPLACE_FIELD),
                )
                BarButton("Replace", "Replace", theme, enabled = hasMatches) { onReplace(fields.replacementField.text) }
                BarButton("Replace All", "Replace All", theme, enabled = hasMatches) { onReplaceAll(fields.replacementField.text) }
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
