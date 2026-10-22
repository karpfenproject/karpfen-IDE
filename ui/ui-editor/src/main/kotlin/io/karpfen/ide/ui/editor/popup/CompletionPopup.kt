package io.karpfen.ide.ui.editor.popup

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.HighlightStyle
import io.karpfen.ide.ui.editor.model.completion.CompletionUiState
import io.karpfen.ide.ui.editor.theme.EditorTheme

/**
 * The completion list: anchored below (or above, if there is no room) [anchor], at most
 * [CompletionUiState.MAX_VISIBLE_ROWS] visible rows, each with a kind badge, the label (matched prefix emphasized) and
 * the detail; the selected row is kept visible. Mouse: a click selects ([onSelect]), a double click accepts
 * ([onAccept]). The list never takes keyboard focus; keys are handled by the model while the text area keeps focus.
 */
@Composable
internal fun CompletionPopup(
    state: CompletionUiState,
    anchor: Rect,
    theme: EditorTheme,
    textStyle: TextStyle,
    onSelect: (Int) -> Unit,
    onAccept: () -> Unit,
) {
    val metrics = rememberCompletionListMetrics(textStyle)
    AnchoredPopupLayout(anchor) {
        CompletionList(state, metrics, theme, textStyle, onSelect, onAccept)
    }
}

/** Fixed measures of the completion list. */
private object CompletionListDefaults {
    /** Vertical space added to a text line to make a row. */
    val RowPadding: Dp = 4.dp

    /** Width of the kind badge. */
    val BadgeWidth: Dp = 22.dp

    /** Space at the start and end of a row. */
    val HorizontalPadding: Dp = 4.dp

    /** Space between the badge and the label. */
    val BadgeGap: Dp = 6.dp

    /** Width reserved for the scrollbar of a long list. */
    val ScrollbarAllowance: Dp = 12.dp

    /** The list's smallest width. */
    val MinWidth: Dp = 200.dp

    /** The list's largest width; longer rows are cut with an ellipsis. */
    val MaxWidth: Dp = 640.dp

    /** Font size of the badge text relative to the editor's. */
    const val BADGE_FONT_SCALE = 0.85f

    /** Opacity of the badge's tinted background. */
    const val BADGE_BACKGROUND_ALPHA = 0.18f
}

/** The list's measures for [textStyle]: one monospace character and line measured once per style and density. */
@Composable
private fun rememberCompletionListMetrics(textStyle: TextStyle): CompletionListMetrics {
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val density = LocalDensity.current
    return remember(textStyle, density, measurer) {
        val sample = measurer.measure("0", textStyle)
        with(density) {
            CompletionListMetrics(
                rowHeight = sample.size.height + CompletionListDefaults.RowPadding.roundToPx(),
                charWidth = sample.size.width.toFloat(),
                minWidth = CompletionListDefaults.MinWidth.roundToPx(),
                maxWidth = CompletionListDefaults.MaxWidth.roundToPx(),
                rowChrome = (
                    CompletionListDefaults.BadgeWidth + CompletionListDefaults.BadgeGap +
                        CompletionListDefaults.HorizontalPadding * 2 + CompletionListDefaults.ScrollbarAllowance
                    ).roundToPx(),
            )
        }
    }
}

/** The scrollable list of [state]'s items in a popup surface sized by [metrics]; keeps the selected row visible. */
@Composable
private fun CompletionList(
    state: CompletionUiState,
    metrics: CompletionListMetrics,
    theme: EditorTheme,
    textStyle: TextStyle,
    onSelect: (Int) -> Unit,
    onAccept: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState, state) { listState.reveal(state.selectedIndex) }
    val density = LocalDensity.current
    val width = with(density) { metrics.width(state.items).toDp() }
    val height = with(density) { metrics.height(state.items.size).toDp() }
    val rowHeight = with(density) { metrics.rowHeight.toDp() }
    PopupSurface(theme, Modifier.width(width).heightIn(max = height).testTag(EditorPopupTags.COMPLETION)) {
        LazyColumn(state = listState) {
            itemsIndexed(state.items) { index, item ->
                CompletionRow(item, index, index == state.selectedIndex, state.filterText, rowHeight, theme, textStyle, onSelect, onAccept)
            }
        }
        if (state.items.size > CompletionUiState.MAX_VISIBLE_ROWS) {
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/**
 * One row: badge, label (the matched prefix in bold) and detail, highlighted when [selected]; a press selects it and a
 * double tap accepts the selection.
 */
@Composable
private fun CompletionRow(
    item: CompletionItem,
    index: Int,
    selected: Boolean,
    filterText: String,
    rowHeight: Dp,
    theme: EditorTheme,
    textStyle: TextStyle,
    onSelect: (Int) -> Unit,
    onAccept: () -> Unit,
) {
    val select by rememberUpdatedState(onSelect)
    val accept by rememberUpdatedState(onAccept)
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(if (selected) theme.popupSelection else Color.Transparent)
            .pointerInput(index) { detectTapGestures(onPress = { select(index) }, onDoubleTap = { accept() }) }
            .semantics(mergeDescendants = true) { this.selected = selected }
            .testTag(EditorPopupTags.completionRow(index))
            .padding(horizontal = CompletionListDefaults.HorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindBadgeView(KindBadge.of(item.kind), theme, textStyle, rowHeight)
        Spacer(Modifier.width(CompletionListDefaults.BadgeGap))
        BasicText(
            text = CompletionText.label(item.label, filterText, SpanStyle(fontWeight = FontWeight.Bold)),
            style = textStyle.merge(TextStyle(color = theme.foreground)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        item.detail?.let { detail ->
            BasicText(
                text = detail,
                style = textStyle.merge(TextStyle(color = theme.foreground.copy(alpha = PopupStyle.MUTED_ALPHA))),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** The kind badge: [badge]'s text in the colour of its highlight style on a tinted background. */
@Composable
private fun KindBadgeView(badge: KindBadge, theme: EditorTheme, textStyle: TextStyle, rowHeight: Dp) {
    val color = theme.colorOf(badge.style)
    val fontSize = textStyle.fontSize.takeIf { it.isSpecified }?.times(CompletionListDefaults.BADGE_FONT_SCALE) ?: textStyle.fontSize
    Box(
        Modifier
            .width(CompletionListDefaults.BadgeWidth)
            .height(rowHeight - CompletionListDefaults.RowPadding)
            .background(color.copy(alpha = CompletionListDefaults.BADGE_BACKGROUND_ALPHA), PopupStyle.shape)
            .semantics { contentDescription = badge.description },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(badge.text, style = textStyle.merge(TextStyle(color = color, fontSize = fontSize, fontWeight = FontWeight.Bold)), maxLines = 1)
    }
}

/** The text colour of [style] in this theme, or the foreground if the theme leaves it unstyled. */
private fun EditorTheme.colorOf(style: HighlightStyle): Color = styles[style]?.color?.takeIf { it.isSpecified } ?: foreground
