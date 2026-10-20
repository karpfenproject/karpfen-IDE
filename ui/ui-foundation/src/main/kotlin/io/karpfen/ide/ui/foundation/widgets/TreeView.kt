package io.karpfen.ide.ui.foundation.widgets

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import io.karpfen.ide.ui.foundation.keys.compose.toKeyStroke
import kotlinx.coroutines.flow.first

/**
 * A node of a [TreeView]. [key] identifies the node across recompositions (e.g. a path); [children] is null for a
 * leaf and a (possibly empty) list for an expandable node.
 */
@Immutable
public data class TreeNode<K>(
    public val key: K,
    public val label: String,
    public val children: List<TreeNode<K>>? = null,
    public val icon: ImageVector? = null,
)

/**
 * An entry of a context menu: [label], whether it [isEnabled], and what happens [onClick]. Holds behaviour, so it is a
 * plain class with identity equality (not a data class).
 */
@Immutable
public class ContextMenuAction(
    public val label: String,
    public val isEnabled: Boolean = true,
    public val onClick: () -> Unit,
)

/** Test tags of [TreeView] (for headless UI tests of the foundation and its users). */
public object TreeViewTestTags {
    /** The focusable tree container. */
    public const val TREE: String = "TreeView"

    /** The row of the node [key] (its `toString()` is part of the tag). */
    public fun row(key: Any?): String = "TreeView.row:$key"
}

/**
 * A lazy tree (spec §6) with expand/collapse, single selection and keyboard navigation (Up/Down move the selection,
 * Left collapses or moves to the parent, Right expands or moves to the first child, Enter opens). Double-click opens.
 * Stateless: [expanded] and [selected] are hoisted.
 *
 * Whenever [selected] changes — by keyboard, by click or programmatically (e.g. the workbench revealing a newly created
 * file after expanding its ancestors) — the tree scrolls minimally so the selected row is visible (MUST, tested).
 *
 * Further behaviour: Home/End select the first/last row; a click selects its row on press and focuses the tree, a
 * click on an expandable node's chevron toggles it without selecting; a right-click selects its row and opens the
 * node's [contextMenu] at the pointer (disabled entries greyed out). Keys must be unique within the tree; a [selected]
 * key that is not visible (a collapsed ancestor) is revealed as soon as its row appears. Needs `KarpfenTheme`.
 *
 * @param icon slot rendering a node's icon; null uses [TreeNode.icon].
 * @param contextMenu the context-menu entries of a node (empty: no menu).
 */
@Composable
public fun <K> TreeView(
    roots: List<TreeNode<K>>,
    expanded: Set<K>,
    selected: K?,
    onToggleExpanded: (K) -> Unit,
    onSelect: (K) -> Unit,
    onOpen: (K) -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable (TreeNode<K>) -> Unit)? = null,
    contextMenu: (TreeNode<K>) -> List<ContextMenuAction> = { emptyList() },
) {
    val rows = remember(roots, expanded) { TreeRows.flatten(roots, expanded) }
    val selectedIndex = if (selected == null) -1 else rows.indexOfFirst { it.node.key == selected }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<TreeRowMenu<K>?>(null) }
    val doubleClickTimeout = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val pointerPolicy = remember(doubleClickTimeout) { TreePointerPolicy(doubleClickTimeout) }
    val density = LocalDensity.current

    fun dispatch(intent: TreeIntent<K>) {
        when (intent) {
            is TreeIntent.Select -> if (intent.key != selected) onSelect(intent.key)
            is TreeIntent.Toggle -> onToggleExpanded(intent.key)
            is TreeIntent.Open -> onOpen(intent.key)
        }
    }

    fun onPress(row: TreeRow<K>, press: TreeRowPress) {
        focusRequester.requestFocus()
        when (press) {
            is TreeRowPress.Primary -> {
                val chevronStart = with(density) { TreeViewDefaults.chevronStart(row.depth).toPx() }
                val chevronEnd = chevronStart + with(density) { TreeViewDefaults.ChevronWidth.toPx() }
                pointerPolicy.primaryPress(row, press.x, press.uptimeMillis, chevronStart, chevronEnd).forEach(::dispatch)
            }
            is TreeRowPress.Secondary -> {
                dispatch(TreeIntent.Select(row.node.key))
                val actions = contextMenu(row.node)
                if (actions.isNotEmpty()) {
                    val offset = with(density) {
                        DpOffset(press.position.x.toDp(), press.position.y.toDp() - TreeViewDefaults.RowHeight)
                    }
                    menu = TreeRowMenu(row.node.key, actions, offset)
                }
            }
        }
    }

    RevealSelectionEffect(selected, rows, listState)
    // A menu whose row vanished (its node was removed or collapsed away) is closed, so it never reappears with
    // stale entries when a node with the same key shows up again.
    LaunchedEffect(rows) {
        val key = menu?.key ?: return@LaunchedEffect
        if (rows.none { it.node.key == key }) menu = null
    }

    Box(
        modifier
            .testTag(TreeViewTestTags.TREE)
            .onKeyEvent { event ->
                val key = event.toKeyStroke()?.let(TreeNavigation::keyOf) ?: return@onKeyEvent false
                val intent = TreeNavigation.intentFor(key, rows, expanded, selectedIndex) ?: return@onKeyEvent false
                dispatch(intent)
                true
            }
            .onFocusChanged { isFocused = it.hasFocus }
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(rows, key = { row -> TreeRowKey(row.node.key) }) { row ->
                TreeRowView(
                    row = row,
                    isSelected = row.node.key == selected,
                    isExpanded = row.node.key in expanded,
                    isTreeFocused = isFocused,
                    icon = icon,
                    menu = menu?.takeIf { it.key == row.node.key },
                    onPress = { press -> onPress(row, press) },
                    onDismissMenu = {
                        menu = null
                        focusRequester.requestFocus()
                    },
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/** A lazy-list key wrapping a node key (lazy keys must not be null). */
private data class TreeRowKey(val key: Any?)

/**
 * Scrolls minimally so the row of [selected] is visible, whenever [selected] changes; waits until the row is visible
 * (its ancestors expanded) and the list is laid out. Rows have the fixed height [TreeViewDefaults.RowHeight], so the
 * target offset is exact even for rows that are not composed.
 */
@Composable
private fun <K> RevealSelectionEffect(selected: K?, rows: List<TreeRow<K>>, listState: LazyListState) {
    val currentRows = rememberUpdatedState(rows)
    val rowHeight = with(LocalDensity.current) { TreeViewDefaults.RowHeight.roundToPx() }
    LaunchedEffect(selected, rowHeight) {
        if (selected == null) return@LaunchedEffect
        val index = snapshotFlow { currentRows.value.indexOfFirst { it.node.key == selected } }.first { it >= 0 }
        val viewport = snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        val position = listState.firstVisibleItemIndex * rowHeight + listState.firstVisibleItemScrollOffset
        ScrollReveal.target(index * rowHeight, (index + 1) * rowHeight, position, viewport)?.let { target ->
            listState.scrollToItem(target / rowHeight, target % rowHeight)
        }
    }
}
