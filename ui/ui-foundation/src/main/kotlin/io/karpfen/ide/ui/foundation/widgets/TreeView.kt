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
 * A node of a [TreeView]. [key] identifies the node across recompositions, for example a path. [children] is null for
 * a leaf and a list, possibly empty, for an expandable node.
 *
 * @property label the text of the node's row.
 * @property icon drawn untinted before the label when the tree has no icon slot, or null for none.
 */
@Immutable
public data class TreeNode<K>(
    public val key: K,
    public val label: String,
    public val children: List<TreeNode<K>>? = null,
    public val icon: ImageVector? = null,
)

/**
 * An entry of a context menu: its [label], whether it [isEnabled], and what happens [onClick]. It holds behaviour, so
 * it is a plain class with identity equality and not a data class.
 */
@Immutable
public class ContextMenuAction(
    public val label: String,
    public val isEnabled: Boolean = true,
    public val onClick: () -> Unit,
)

/** Test tags of [TreeView], for headless UI tests of the foundation and of the modules that use it. */
public object TreeViewTestTags {
    /** The focusable tree container. */
    public const val TREE: String = "TreeView"

    /** The row of the node [key]. The key's `toString()` is part of the tag. */
    public fun row(key: Any?): String = "TreeView.row:$key"
}

/**
 * A lazy tree with expanding and collapsing, single selection and keyboard navigation. Up and Down move the selection.
 * Left collapses a node or moves to the parent. Right expands a node or moves to the first child. Enter opens a node,
 * and so does a double click. The tree is stateless, so [expanded] and [selected] are hoisted.
 *
 * Whenever [selected] changes, the tree scrolls minimally so the selected row is visible. This holds for a change by
 * keyboard, by click and by the program, for example when the workbench reveals a newly created file after expanding
 * its ancestors. Tests check this behaviour.
 *
 * Home and End select the first and the last row. A click selects its row on press and focuses the tree. A click on
 * the chevron of an expandable node toggles the node without selecting it. A right click selects its row and opens
 * the node's [contextMenu] at the pointer, with disabled entries greyed out. A context menu closes when its row
 * vanishes because its node was removed or collapsed away. So the menu never comes back with stale entries when a
 * node with the same key shows up again. Keys must be unique within the tree. A [selected] key whose row is not
 * visible, because an ancestor is collapsed, is revealed as soon as its row appears. The tree needs `KarpfenTheme`.
 *
 * @param icon a slot that renders a node's icon. When it is null, the tree draws [TreeNode.icon].
 * @param contextMenu the context-menu entries of a node. An empty list means the node has no menu.
 * @param onClick called for every primary click on a row outside its chevron, after [onSelect]. It is called on the
 *   selected row too, which [onSelect] does not report. This serves trees whose selection follows something else,
 *   such as the Outline, whose selection follows the node at the caret. There a click can act although it selects
 *   nothing new. Keys never count as clicks.
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
    onClick: (K) -> Unit = {},
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
            is TreeIntent.Click -> onClick(intent.key)
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

/** A lazy-list key that wraps a node key, because lazy-list keys must not be null. */
private data class TreeRowKey(val key: Any?)

/**
 * Scrolls minimally so the row of [selected] is visible whenever [selected] changes. It waits until the row exists,
 * which needs all its ancestors expanded, and until the list is laid out. Rows have the fixed height
 * [TreeViewDefaults.RowHeight], so the target offset is exact even for rows that are not composed.
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
