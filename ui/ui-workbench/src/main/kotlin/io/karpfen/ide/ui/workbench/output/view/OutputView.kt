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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import io.karpfen.ide.ui.workbench.output.OutputReadingPosition
import io.karpfen.ide.ui.workbench.output.OutputUiState

/**
 * The Language Tools Output tool window: a toolbar with the Follow toggle and Clear, above the buffered lines in a
 * monospace font, each with its time, level (coloured by severity), the logger's simple name, the file it read and the
 * message, as [OutputLineFormat] writes them. While [OutputUiState.follow] is on, the list scrolls to the newest line
 * whenever one arrives, showing it from its start. Following yields to the user: scrolling the newest line out of sight
 * switches it off, and scrolling it back into sight switches it on again, both through [onFollowChange]. The view
 * reports where the user reads to [onReadingPositionChange] and, when it enters the composition again, as when its
 * hidden tool window is shown, starts at [readingPosition]. The text can be selected and copied. Without lines it shows
 * [emptyText]; with [errorsInErrorColour], the whole text of an error line is in the error colour, as the runtime log
 * shows standard error.
 */
@Composable
internal fun OutputView(
    state: OutputUiState,
    onFollowChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    readingPosition: OutputReadingPosition? = null,
    onReadingPositionChange: (OutputReadingPosition) -> Unit = {},
    emptyText: String = "No output from the language tools",
    errorsInErrorColour: Boolean = false,
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
                    emptyText,
                    color = KarpfenTheme.colors.textMuted,
                    style = KarpfenTheme.typography.uiSmall,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                OutputLines(state.lines, state.follow, onFollowChange, readingPosition, onReadingPositionChange, errorsInErrorColour)
            }
        }
    }
}

/**
 * The scrollable, selectable list of [lines]; scrolls to the newest line when one arrives while [follow] is on, and
 * reports the user's scrolling to [onFollowChange] ([FollowUserScrolling]). It starts at [readingPosition] and reports
 * every change of the line at its top to [onReadingPositionChange]; [errorsInErrorColour] colours whole error lines.
 */
@Composable
private fun OutputLines(
    lines: List<OutputLine>,
    follow: Boolean,
    onFollowChange: (Boolean) -> Unit,
    readingPosition: OutputReadingPosition?,
    onReadingPositionChange: (OutputReadingPosition) -> Unit,
    errorsInErrorColour: Boolean,
) {
    val format = remember { OutputLineFormat() }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = readingPosition?.indexIn(lines) ?: 0,
        initialFirstVisibleItemScrollOffset = readingPosition?.offsetIn(lines) ?: 0,
    )
    val currentLines by rememberUpdatedState(lines)
    val currentOnReadingPositionChange by rememberUpdatedState(onReadingPositionChange)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            currentLines.getOrNull(index)?.let { currentOnReadingPositionChange(OutputReadingPosition(it.sequence, offset)) }
        }
    }
    val currentFollow by rememberUpdatedState(follow)
    val currentOnFollowChange by rememberUpdatedState(onFollowChange)
    val userScrolling = remember(listState) {
        FollowUserScrolling(listState, isFollowing = { currentFollow }, onFollowChange = { currentOnFollowChange(it) })
    }
    val newest = lines.last().sequence
    LaunchedEffect(newest, follow) {
        if (follow) listState.scrollToItem(lines.lastIndex)
    }
    val listAdapter = rememberScrollbarAdapter(listState)
    val scrollbarAdapter = remember(userScrolling, listAdapter) { userScrolling.scrollbar(listAdapter) }
    Box(Modifier.fillMaxSize().nestedScroll(userScrolling)) {
        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag(OutputViewTestTags.LINES)) {
                items(lines, key = { it.sequence }) { line -> OutputLineRow(line, format, errorsInErrorColour) }
            }
        }
        VerticalScrollbar(
            adapter = scrollbarAdapter,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag(OutputViewTestTags.SCROLLBAR),
        )
    }
}

/**
 * One [line] as one text: muted time, logger and file, the level in its severity's colour, the message in the text
 * colour, or all of it in the error colour for an error line with [errorsInErrorColour].
 */
@Composable
private fun OutputLineRow(line: OutputLine, format: OutputLineFormat, errorsInErrorColour: Boolean) {
    val colors = KarpfenTheme.colors
    val wholeLineInError = errorsInErrorColour && line.level == OutputLevel.ERROR
    Text(
        text = lineText(line, format, colors, wholeLineInError),
        color = if (wholeLineInError) colors.error else colors.text,
        style = KarpfenTheme.typography.monospace,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag(OutputViewTestTags.line(line.sequence)),
    )
}

/** The styled text of [line] in [colors]; [inError] writes every part in the error colour. */
private fun lineText(
    line: OutputLine,
    format: OutputLineFormat,
    colors: KarpfenColors,
    inError: Boolean,
): AnnotatedString = buildAnnotatedString {
    val muted = if (inError) colors.error else colors.textMuted
    withStyle(SpanStyle(color = muted)) { append("${format.time(line)} ") }
    withStyle(SpanStyle(color = levelColor(line.level, colors))) { append("${format.level(line)} ") }
    withStyle(SpanStyle(color = muted)) {
        append(format.logger(line))
        format.file(line)?.let { append(" $it") }
        append(" - ")
    }
    append(line.message)
}

/** The colour of [level]: the severity colours for errors, warnings and information, muted for debugging detail. */
private fun levelColor(level: OutputLevel, colors: KarpfenColors): Color = when (level) {
    OutputLevel.ERROR -> colors.error
    OutputLevel.WARNING -> colors.warning
    OutputLevel.INFO -> colors.info
    OutputLevel.DEBUG, OutputLevel.TRACE -> colors.textMuted
}
