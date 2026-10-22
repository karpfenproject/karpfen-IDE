package io.karpfen.ide.ui.workbench.problems.view

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import io.karpfen.ide.ui.foundation.widgets.TreeNode
import io.karpfen.ide.ui.workbench.problems.ProblemItem
import io.karpfen.ide.ui.workbench.problems.ProblemsUiState
import java.nio.file.Path

/** The key of a row of the Problems tree: a file group or one of its items. */
internal sealed interface ProblemKey {
    /** The group of the file [path]. */
    data class Group(val path: Path) : ProblemKey

    /** The [index]-th item of the group of [path]. */
    data class Item(val path: Path, val index: Int) : ProblemKey
}

/**
 * The `TreeView` nodes of the Problems view: one node per file (`displayPath (n)`) with one child per problem (severity
 * icon, `message (line:col)`), and the item behind each key for navigation.
 */
internal class ProblemsTreeNodes(state: ProblemsUiState) {

    private val items = HashMap<ProblemKey, ProblemItem>()

    /** One node per file group. */
    val roots: List<TreeNode<ProblemKey>> = state.groups.map { group ->
        TreeNode(
            key = ProblemKey.Group(group.path),
            label = "${group.displayPath} (${group.items.size})",
            children = group.items.mapIndexed { index, item ->
                val key = ProblemKey.Item(group.path, index)
                items[key] = item
                TreeNode(key, "${item.diagnostic.message} (${item.line}:${item.column})", icon = iconOf(item.diagnostic.severity))
            },
            icon = KarpfenIcons.File,
        )
    }

    /** Every group key. */
    val groups: Set<ProblemKey> = roots.mapTo(LinkedHashSet()) { it.key }

    /** The problem of [key], or null for a group. */
    fun itemOf(key: ProblemKey): ProblemItem? = items[key]

    private fun iconOf(severity: Severity) = when (severity) {
        Severity.ERROR -> KarpfenIcons.Error
        Severity.WARNING -> KarpfenIcons.Warning
        Severity.INFO, Severity.HINT -> KarpfenIcons.Info
    }
}
