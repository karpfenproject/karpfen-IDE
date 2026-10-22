package io.karpfen.ide.ui.workbench.output.view

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.theme.KarpfenColors
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.output.OutputLevel
import io.karpfen.ide.ui.workbench.output.OutputLine
import io.karpfen.ide.ui.workbench.output.OutputLineFormat
import io.karpfen.ide.ui.workbench.output.OutputUiState

/**
 * The Language Tools Output tool window: a toolbar with the Follow toggle and Clear, above the buffered lines in a
 * monospace font, each with its time, level (coloured by severity), the logger's simple name and the message, as
 * [OutputLineFormat] writes them. While [OutputUiState.follow] is on, the list scrolls to the newest line whenever one
 * arrives. The text can be selected and copied.
 */
@Composable
internal fun OutputView(
    state: OutputUiState,
    onFollowChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = state.follow,
                onClick = { onFollowChange(!state.follow) },
                label = { Text("Follow", style = KarpfenTheme.typography.uiSmall) },
                modifier = Modifier.testTag(OutputViewTestTags.FOLLOW),
            )
            TextButton(onClick = onClear, enabled = state.lines.isNotEmpty(), modifier = Modifier.testTag(OutputViewTestTags.CLEAR)) {
                Text("Clear", style = KarpfenTheme.typography.uiSmall)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.lines.isEmpty()) {
                Text(
                    "No output from the language tools",
                    color = KarpfenTheme.colors.textMuted,
                    style = KarpfenTheme.typography.uiSmall,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                OutputLines(state.lines, state.follow)
            }
        }
    }
}

/** The scrollable, selectable list of [lines]; scrolls to the newest line when one arrives while [follow] is on. */
@Composable
private fun OutputLines(lines: List<OutputLine>, follow: Boolean) {
    val format = remember { OutputLineFormat() }
    val listState = rememberLazyListState()
    val newest = lines.last().sequence
    LaunchedEffect(newest, follow) {
        if (follow) listState.scrollToItem(lines.lastIndex)
    }
    Box(Modifier.fillMaxSize()) {
        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag(OutputViewTestTags.LINES)) {
                items(lines, key = { it.sequence }) { line -> OutputLineRow(line, format) }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/** One [line] as one text: muted time and logger, the level in its severity's colour, the message in the text colour. */
@Composable
private fun OutputLineRow(line: OutputLine, format: OutputLineFormat) {
    val colors = KarpfenTheme.colors
    Text(
        text = lineText(line, format, colors),
        color = colors.text,
        style = KarpfenTheme.typography.monospace,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag(OutputViewTestTags.line(line.sequence)),
    )
}

/** The styled text of [line] in [colors]. */
private fun lineText(line: OutputLine, format: OutputLineFormat, colors: KarpfenColors): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = colors.textMuted)) { append("${format.time(line)} ") }
    withStyle(SpanStyle(color = levelColor(line.level, colors))) { append("${format.level(line)} ") }
    withStyle(SpanStyle(color = colors.textMuted)) { append("${format.logger(line)} - ") }
    append(line.message)
}

/** The colour of [level]: the severity colours for errors, warnings and information, muted for debugging detail. */
private fun levelColor(level: OutputLevel, colors: KarpfenColors): Color = when (level) {
    OutputLevel.ERROR -> colors.error
    OutputLevel.WARNING -> colors.warning
    OutputLevel.INFO -> colors.info
    OutputLevel.DEBUG, OutputLevel.TRACE -> colors.textMuted
}
