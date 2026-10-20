package io.karpfen.ide.ui.foundation.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

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

/**
 * A lazy tree (spec §6) with expand/collapse, single selection and keyboard navigation (Up/Down move the selection,
 * Left collapses or moves to the parent, Right expands or moves to the first child, Enter opens). Double-click opens.
 * Stateless: [expanded] and [selected] are hoisted.
 *
 * Whenever [selected] changes — by keyboard, by click or programmatically (e.g. the workbench revealing a newly created
 * file after expanding its ancestors) — the tree scrolls minimally so the selected row is visible (MUST, tested).
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
): Unit = TODO("WP-FOUNDATION: lazy tree view")
