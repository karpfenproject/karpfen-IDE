package io.karpfen.ide.ui.foundation.widgets

/**
 * A visible row of a [TreeView]: [node] at nesting [depth] (0 for roots); [parentIndex] is the index of the parent's
 * row in the same row list, or -1 for a root.
 */
internal data class TreeRow<K>(val node: TreeNode<K>, val depth: Int, val parentIndex: Int) {
    /** Whether the node can be expanded (it has a children list, possibly empty). */
    val isExpandable: Boolean get() = node.children != null
}

/** The rows a [TreeView] shows: the pre-order of the nodes whose ancestors are all expanded (pure). */
internal object TreeRows {

    /**
     * The visible rows of [roots] when the nodes with the keys in [expanded] are expanded. The pre-order traversal is
     * iterative (an explicit stack of pending rows), so deep trees cannot overflow the call stack.
     */
    fun <K> flatten(roots: List<TreeNode<K>>, expanded: Set<K>): List<TreeRow<K>> {
        val rows = ArrayList<TreeRow<K>>()
        val pending = ArrayDeque<TreeRow<K>>()
        roots.asReversed().forEach { pending.addFirst(TreeRow(it, depth = 0, parentIndex = -1)) }
        while (pending.isNotEmpty()) {
            val row = pending.removeFirst()
            val index = rows.size
            rows += row
            val children = row.node.children
            if (children != null && row.node.key in expanded) {
                children.asReversed().forEach { child -> pending.addFirst(TreeRow(child, row.depth + 1, index)) }
            }
        }
        return rows
    }
}
