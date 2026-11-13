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
package io.karpfen.ide.ui.workbench.problems.view

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import io.karpfen.ide.ui.foundation.widgets.TreeNode
import io.karpfen.ide.ui.workbench.problems.ProblemGroup
import io.karpfen.ide.ui.workbench.problems.ProblemItem
import io.karpfen.ide.ui.workbench.problems.ProblemsUiState
import java.nio.file.Path

/** The two parts of the Problems tree, each a top node. */
internal enum class ProblemSection(val title: String) {
    /** What the continuous analysis finds while the user types. */
    LOCAL("Local"),

    /** What only a validation of the whole project finds. */
    GLOBAL("Global"),
}

/**
 * The key of a row of the Problems tree. A row is a top node, a file group below one, or one of the group's items. The
 * same file can have a group under both top nodes, so every key names its [section].
 */
internal sealed interface ProblemKey {
    /** The top node the row belongs to, or is. */
    val section: ProblemSection

    /** The top node [section]. */
    data class Section(override val section: ProblemSection) : ProblemKey

    /** The group of the file [path] below the top node [section]. */
    data class Group(override val section: ProblemSection, val path: Path) : ProblemKey

    /** The item at [index] in the group of [path] below the top node [section]. */
    data class Item(override val section: ProblemSection, val path: Path, val index: Int) : ProblemKey
}

/**
 * The `TreeView` nodes of the Problems view. While a folder is open there are two top nodes, Local and Global, in this
 * order. Each is labelled with its title and the number of problems below it that pass the filter, as `Local (n)`.
 * Before the first validation the Global node reads `Global (not validated yet)`. A top node without problems is a
 * leaf. Below a top node there is one node per file, labelled `displayPath (n)`, with one child per problem, which
 * carries the severity icon and the label `message (line:col)`. Without an open folder there are no nodes. The class
 * also keeps the item behind each key for navigation.
 */
internal class ProblemsTreeNodes(state: ProblemsUiState) {

    private val items = HashMap<ProblemKey, ProblemItem>()

    /** The two top nodes while a folder is open, otherwise none. */
    val roots: List<TreeNode<ProblemKey>> =
        if (!state.hasFolder) {
            emptyList()
        } else {
            listOf(section(ProblemSection.LOCAL, state.local), section(ProblemSection.GLOBAL, state.global))
        }

    /** Every key of a node with children: the top nodes with problems and all file groups. */
    val expandable: Set<ProblemKey> = LinkedHashSet<ProblemKey>().apply {
        roots.forEach { root ->
            root.children?.let { groups -> add(root.key); groups.forEach { add(it.key) } }
        }
    }

    /** The problem of [key], or null if [key] is a top node or a group. */
    fun itemOf(key: ProblemKey): ProblemItem? = items[key]

    /**
     * The top node [section] over [groups], null before the first validation. The Global node starts a section of the
     * tree, so a line divides it from the Local node.
     */
    private fun section(section: ProblemSection, groups: List<ProblemGroup>?): TreeNode<ProblemKey> {
        val count = groups?.let { list -> list.sumOf { it.items.size }.toString() } ?: NOT_VALIDATED
        return TreeNode(
            key = ProblemKey.Section(section),
            label = "${section.title} ($count)",
            children = groups?.takeIf { it.isNotEmpty() }?.map { group(section, it) },
            startsSection = section == ProblemSection.GLOBAL,
        )
    }

    /** The node of [group] below the top node [section], with one child per item. */
    private fun group(section: ProblemSection, group: ProblemGroup): TreeNode<ProblemKey> = TreeNode(
        key = ProblemKey.Group(section, group.path),
        label = "${group.displayPath} (${group.items.size})",
        children = group.items.mapIndexed { index, item ->
            val key = ProblemKey.Item(section, group.path, index)
            items[key] = item
            TreeNode(key, "${item.diagnostic.message} (${item.line}:${item.column})", icon = iconOf(item.diagnostic.severity))
        },
        icon = KarpfenIcons.File,
    )

    /** The icon of a problem of [severity]. Hints show the info icon. */
    private fun iconOf(severity: Severity) = when (severity) {
        Severity.ERROR -> KarpfenIcons.Error
        Severity.WARNING -> KarpfenIcons.Warning
        Severity.INFO, Severity.HINT -> KarpfenIcons.Info
    }

    private companion object {
        /** The count of the Global node before the first validation of the open folder. */
        const val NOT_VALIDATED = "not validated yet"
    }
}
