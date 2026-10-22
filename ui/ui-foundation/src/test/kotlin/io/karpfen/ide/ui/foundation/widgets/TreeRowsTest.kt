package io.karpfen.ide.ui.foundation.widgets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The visible rows of [TreeRows.flatten]: pre-order, hidden children of collapsed ancestors, depths and parents. */
class TreeRowsTest {

    private val tree = listOf(
        TreeNode(
            "src",
            "src",
            children = listOf(
                TreeNode("src/a", "a", children = listOf(TreeNode("src/a/x.kmeta", "x.kmeta"))),
                TreeNode("src/b.kstates", "b.kstates"),
                TreeNode("src/empty", "empty", children = emptyList()),
            ),
        ),
        TreeNode("README.md", "README.md"),
    )

    private fun keys(expanded: Set<String>) = TreeRows.flatten(tree, expanded).map { it.node.key }

    @Test
    fun `collapsed roots show only the roots`() {
        assertEquals(listOf("src", "README.md"), keys(emptySet()))
    }

    @Test
    fun `expanded nodes show their children in pre-order below them`() {
        assertEquals(listOf("src", "src/a", "src/b.kstates", "src/empty", "README.md"), keys(setOf("src")))
        assertEquals(
            listOf("src", "src/a", "src/a/x.kmeta", "src/b.kstates", "src/empty", "README.md"),
            keys(setOf("src", "src/a")),
        )
    }

    @Test
    fun `children of a collapsed ancestor stay hidden even when they are expanded`() {
        assertEquals(listOf("src", "README.md"), keys(setOf("src/a")))
    }

    @Test
    fun `rows carry depth and the index of their parent row`() {
        val rows = TreeRows.flatten(tree, setOf("src", "src/a"))

        assertEquals(listOf(0, 1, 2, 1, 1, 0), rows.map { it.depth })
        assertEquals(listOf(-1, 0, 1, 0, 0, -1), rows.map { it.parentIndex })
        assertEquals(listOf(true, true, false, false, true, false), rows.map { it.isExpandable })
    }

    @Test
    fun `an empty forest has no rows and deep trees do not overflow the stack`() {
        assertTrue(TreeRows.flatten(emptyList<TreeNode<Int>>(), emptySet()).isEmpty())

        var deep = TreeNode(10_000, "leaf")
        for (depth in 9_999 downTo 0) deep = TreeNode(depth, "n$depth", children = listOf(deep))
        val rows = TreeRows.flatten(listOf(deep), (0..10_000).toSet())

        assertEquals(10_001, rows.size)
        assertEquals(10_000, rows.last().depth)
        assertEquals(9_999, rows.last().parentIndex)
    }
}
