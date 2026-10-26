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

import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The keyboard policy of [TreeNavigation]: which strokes are tree keys and what each key does on a given tree. */
class TreeNavigationTest {

    /**
     * The tree of the tests, with `root` and `empty` expanded:
     * ```
     * root (expanded) ─ dir (collapsed, has children) ─ (hidden)
     *                 ├ file
     *                 └ empty (expanded, no children)
     * other
     * ```
     */
    private val tree = listOf(
        TreeNode(
            "root",
            "root",
            children = listOf(
                TreeNode("dir", "dir", children = listOf(TreeNode("dir/x", "x"))),
                TreeNode("file", "file"),
                TreeNode("empty", "empty", children = emptyList()),
            ),
        ),
        TreeNode("other", "other"),
    )
    private val expanded = setOf("root", "empty")
    private val rows = TreeRows.flatten(tree, expanded)

    private fun intent(key: TreeKey, selected: String?, expanded: Set<String> = this.expanded): TreeIntent<String>? {
        val rows = TreeRows.flatten(tree, expanded)
        return TreeNavigation.intentFor(key, rows, expanded, rows.indexOfFirst { it.node.key == selected })
    }

    @Test
    fun `only unmodified navigation keys are tree keys`() {
        assertEquals(TreeKey.UP, TreeNavigation.keyOf(KeyStroke(KeyCode.UP)))
        assertEquals(TreeKey.DOWN, TreeNavigation.keyOf(KeyStroke(KeyCode.DOWN)))
        assertEquals(TreeKey.LEFT, TreeNavigation.keyOf(KeyStroke(KeyCode.LEFT)))
        assertEquals(TreeKey.RIGHT, TreeNavigation.keyOf(KeyStroke(KeyCode.RIGHT)))
        assertEquals(TreeKey.ENTER, TreeNavigation.keyOf(KeyStroke(KeyCode.ENTER)))
        assertEquals(TreeKey.HOME, TreeNavigation.keyOf(KeyStroke(KeyCode.HOME)))
        assertEquals(TreeKey.END, TreeNavigation.keyOf(KeyStroke(KeyCode.END)))
        assertNull(TreeNavigation.keyOf(KeyStroke(KeyCode.DOWN, setOf(KeyModifier.SHIFT))))
        assertNull(TreeNavigation.keyOf(KeyStroke(KeyCode.ENTER, setOf(KeyModifier.META))))
        assertNull(TreeNavigation.keyOf(KeyStroke(KeyCode.A)))
    }

    @Test
    fun `Up and Down move the selection and stop at the ends`() {
        assertEquals(TreeIntent.Select("dir"), intent(TreeKey.DOWN, "root"))
        assertEquals(TreeIntent.Select("file"), intent(TreeKey.DOWN, "dir"))
        assertEquals(TreeIntent.Select("root"), intent(TreeKey.UP, "dir"))
        assertNull(intent(TreeKey.UP, "root"))
        assertNull(intent(TreeKey.DOWN, "other"))
    }

    @Test
    fun `Home and End jump to the first and last row`() {
        assertEquals(TreeIntent.Select("root"), intent(TreeKey.HOME, "file"))
        assertEquals(TreeIntent.Select("other"), intent(TreeKey.END, "file"))
        assertNull(intent(TreeKey.HOME, "root"))
        assertNull(intent(TreeKey.END, "other"))
    }

    @Test
    fun `without a visible selection navigation keys select the first or last row`() {
        assertEquals(TreeIntent.Select("root"), intent(TreeKey.DOWN, null))
        assertEquals(TreeIntent.Select("root"), intent(TreeKey.UP, null))
        assertEquals(TreeIntent.Select("root"), intent(TreeKey.HOME, null))
        assertEquals(TreeIntent.Select("other"), intent(TreeKey.END, null))
        assertEquals(TreeIntent.Select("root"), intent(TreeKey.DOWN, "dir/x"), "a hidden selection counts as none")
        assertNull(intent(TreeKey.LEFT, null))
        assertNull(intent(TreeKey.RIGHT, null))
        assertNull(intent(TreeKey.ENTER, null))
    }

    @Test
    fun `Left collapses an expanded node, otherwise moves to the parent`() {
        assertEquals(TreeIntent.Toggle("root"), intent(TreeKey.LEFT, "root"))
        assertEquals(TreeIntent.Toggle("empty"), intent(TreeKey.LEFT, "empty"))
        assertEquals(TreeIntent.Select("root"), intent(TreeKey.LEFT, "dir"), "collapsed directory")
        assertEquals(TreeIntent.Select("root"), intent(TreeKey.LEFT, "file"), "leaf")
        assertNull(intent(TreeKey.LEFT, "other"), "a collapsed root has no parent")
    }

    @Test
    fun `Right expands a collapsed node, otherwise moves to its first child`() {
        assertEquals(TreeIntent.Toggle("dir"), intent(TreeKey.RIGHT, "dir"))
        assertEquals(TreeIntent.Select("dir"), intent(TreeKey.RIGHT, "root"))
        assertEquals(TreeIntent.Select("dir/x"), intent(TreeKey.RIGHT, "dir", expanded + "dir"))
        assertNull(intent(TreeKey.RIGHT, "empty"), "expanded without children")
        assertNull(intent(TreeKey.RIGHT, "file"), "leaf")
    }

    @Test
    fun `Enter opens the selected node`() {
        assertEquals(TreeIntent.Open("file"), intent(TreeKey.ENTER, "file"))
        assertEquals(TreeIntent.Open("dir"), intent(TreeKey.ENTER, "dir"))
    }

    @Test
    fun `an empty tree handles no key`() {
        TreeKey.entries.forEach { key -> assertNull(TreeNavigation.intentFor(key, emptyList<TreeRow<String>>(), emptySet(), -1)) }
        assertEquals(5, rows.size)
    }
}
