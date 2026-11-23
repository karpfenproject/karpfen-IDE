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
package io.karpfen.ide.ui.workbench.tabs

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Tests the pure transformations of [PaneLayout]: splitting towards each side, closing tabs with the collapse of an
 * empty pane, the focus order, resizing, the replacement of a renamed path and the removal of empty panes.
 */
class PaneLayoutTest {

    private val a: Path = Path.of("/ws/a.kstates").toAbsolutePath()
    private val b: Path = Path.of("/ws/b.kmeta").toAbsolutePath()
    private val c: Path = Path.of("/ws/c.kmodel").toAbsolutePath()

    private fun pane(id: Int) = PaneId(id)

    /** One pane 0 with the tabs a and b, b active. */
    private val single = PaneLayout.single(pane(0), listOf(a, b), b)

    @Test
    fun `a split puts a new pane with the active file on each side and makes it active`() {
        val expectations = mapOf(
            SplitSide.RIGHT to (SplitOrientation.ROW to false),
            SplitSide.LEFT to (SplitOrientation.ROW to true),
            SplitSide.DOWN to (SplitOrientation.COLUMN to false),
            SplitSide.UP to (SplitOrientation.COLUMN to true),
        )
        expectations.forEach { (side, expected) ->
            val split = single.split(pane(0), side, pane(1), SplitId(2))

            val root = assertIs<PaneNode.Split>(split.root)
            assertEquals(expected.first, root.orientation, "$side")
            assertEquals(0.5f, root.share)
            val fresh = PaneNode.Pane(pane(1), listOf(b), b)
            val (first, second) = if (expected.second) fresh to single.root else single.root to fresh
            assertEquals(first, root.first, "$side")
            assertEquals(second, root.second, "$side")
            assertEquals(pane(1), split.activePane)
        }
    }

    @Test
    fun `a pane without tabs and an unknown pane are not split`() {
        val empty = PaneLayout.single(pane(0))
        assertSame(empty, empty.split(pane(0), SplitSide.RIGHT, pane(1), SplitId(2)))
        assertSame(single, single.split(pane(7), SplitSide.RIGHT, pane(1), SplitId(2)))
    }

    @Test
    fun `a pane in the greatest number of splits is not split again, and its neighbours still are`() {
        var deepest = single
        repeat(PaneLayout.MAX_SPLIT_DEPTH) { deepest = deepest.split(deepest.activePane, SplitSide.RIGHT, pane(2 * it + 1), SplitId(2 * it + 2)) }
        assertEquals(PaneLayout.MAX_SPLIT_DEPTH + 1, deepest.panes.size)

        assertSame(deepest, deepest.split(deepest.activePane, SplitSide.DOWN, pane(1000), SplitId(1001)))
        val outer = deepest.withActivePane(pane(0))
        assertEquals(deepest.panes.size + 1, outer.split(pane(0), SplitSide.DOWN, pane(1000), SplitId(1001)).panes.size)
    }

    @Test
    fun `closing the active tab activates the right neighbour, else the left one`() {
        val three = PaneLayout.single(pane(0), listOf(a, b, c), b)
        assertEquals(c, three.withoutTab(pane(0), b).active.active)
        assertEquals(b, three.withActiveTab(pane(0), c).withoutTab(pane(0), c).active.active)
        assertEquals(b, three.withoutTab(pane(0), a).active.active, "an inactive tab leaves the active one")
    }

    @Test
    fun `the only pane stays when its last tab closes`() {
        val emptied = PaneLayout.single(pane(0), listOf(a)).withoutTab(pane(0), a)
        assertEquals(PaneLayout.single(pane(0)), emptied)
    }

    @Test
    fun `an emptied pane disappears and its neighbour takes its place and becomes active`() {
        val right = single.split(pane(0), SplitSide.RIGHT, pane(1), SplitId(2))
        val closed = right.withoutTab(pane(1), b)
        assertEquals(PaneNode.Pane(pane(0), listOf(a, b), b), closed.root)
        assertEquals(pane(0), closed.activePane)

        val left = single.split(pane(0), SplitSide.LEFT, pane(1), SplitId(2)).withActivePane(pane(0))
        val leftClosed = left.withoutTab(pane(1), b)
        assertEquals(pane(0), leftClosed.activePane, "the active pane stays when another pane disappears")
        assertIs<PaneNode.Pane>(leftClosed.root)
    }

    @Test
    fun `the neighbour of a collapsed pane is the pane that lay next to it`() {
        val nested = single
            .split(pane(0), SplitSide.RIGHT, pane(1), SplitId(2))
            .split(pane(1), SplitSide.DOWN, pane(3), SplitId(4))
            .split(pane(0), SplitSide.DOWN, pane(5), SplitId(6))
        assertEquals(listOf(pane(0), pane(5), pane(1), pane(3)), nested.panes.map { it.id })

        val withoutLeftBottom = nested.withActivePane(pane(5)).withoutTab(pane(5), b)
        assertEquals(pane(0), withoutLeftBottom.activePane, "the last pane of the part above")
        val withoutRightTop = nested.withActivePane(pane(1)).withoutTab(pane(1), b)
        assertEquals(pane(3), withoutRightTop.activePane, "the first pane of the part below")
        assertEquals(listOf(pane(0), pane(5), pane(3)), withoutRightTop.panes.map { it.id })
    }

    @Test
    fun `the focus order runs depth first and cycles at both ends`() {
        val layout = single
            .split(pane(0), SplitSide.RIGHT, pane(1), SplitId(2))
            .split(pane(0), SplitSide.UP, pane(3), SplitId(4))
        assertEquals(listOf(pane(3), pane(0), pane(1)), layout.panes.map { it.id })
        assertEquals(pane(0), layout.next(pane(3)))
        assertEquals(pane(3), layout.next(pane(1)))
        assertEquals(pane(1), layout.previous(pane(3)))
        assertEquals(layout.activePane, layout.next(pane(9)))
    }

    @Test
    fun `a resize keeps the share within its bounds`() {
        val split = single.split(pane(0), SplitSide.RIGHT, pane(1), SplitId(2))
        assertEquals(0.3f, assertIs<PaneNode.Split>(split.resized(SplitId(2), 0.3f).root).share)
        assertEquals(PaneLayout.MIN_SHARE, assertIs<PaneNode.Split>(split.resized(SplitId(2), -1f).root).share)
        assertEquals(PaneLayout.MAX_SHARE, assertIs<PaneNode.Split>(split.resized(SplitId(2), 2f).root).share)
        assertEquals(split, split.resized(SplitId(9), 0.3f))
    }

    @Test
    fun `tabs are added after the active one or at an index and moved within bounds`() {
        assertEquals(listOf(a, c, b), single.withActiveTab(pane(0), a).withTab(pane(0), c, 1).active.tabs)
        assertEquals(listOf(c, a, b), single.withTab(pane(0), c, -4).active.tabs)
        assertEquals(listOf(b, a), single.withMovedTab(pane(0), 0, 1).active.tabs)
        assertEquals(single, single.withMovedTab(pane(0), 0, 5))
    }

    @Test
    fun `a renamed path takes the place of the old one in every pane`() {
        val split = single.split(pane(0), SplitSide.RIGHT, pane(1), SplitId(2))
        val renamed = split.withPathReplaced(b, c)
        assertEquals(listOf(listOf(a, c), listOf(c)), renamed.panes.map { it.tabs })
        assertEquals(listOf<Path?>(c, c), renamed.panes.map { it.active })

        val clash = single.withPathReplaced(a, b)
        assertEquals(listOf(b), clash.active.tabs, "a pane that shows the new path keeps one tab of it")
    }

    @Test
    fun `empty panes are removed and the active pane moves to the first remaining one`() {
        val layout = single
            .split(pane(0), SplitSide.RIGHT, pane(1), SplitId(2))
            .split(pane(1), SplitSide.DOWN, pane(3), SplitId(4))
        val emptied = layout.emptied()
        assertEquals(listOf(emptyList<Path>(), emptyList(), emptyList()), emptied.panes.map { it.tabs })
        assertEquals(PaneLayout.single(pane(3)), emptied.withoutEmptyPanes(), "the active pane stays alone")

        val partly = emptied.withTab(pane(0), a, 0).withTab(pane(1), b, 0)
        val pruned = partly.withoutEmptyPanes()
        assertEquals(listOf(pane(0), pane(1)), pruned.panes.map { it.id })
        assertEquals(pane(0), pruned.activePane)
        assertEquals(4, layout.greatestId)
    }

    @Test
    fun `invalid layouts are rejected`() {
        assertFailsWith<IllegalArgumentException> { PaneNode.Pane(pane(0), listOf(a, a)) }
        assertFailsWith<IllegalArgumentException> { PaneNode.Pane(pane(0), listOf(a), b) }
        assertFailsWith<IllegalArgumentException> { PaneLayout(PaneNode.Pane(pane(0)), pane(1)) }
        assertFailsWith<IllegalArgumentException> {
            PaneLayout(PaneNode.Split(SplitId(0), SplitOrientation.ROW, 0.5f, PaneNode.Pane(pane(0)), PaneNode.Pane(pane(1))), pane(0))
        }
        assertFailsWith<IllegalArgumentException> {
            PaneNode.Split(SplitId(2), SplitOrientation.ROW, 1f, PaneNode.Pane(pane(0)), PaneNode.Pane(pane(1)))
        }
        assertEquals(PaneNode.Pane(pane(0), listOf(a, b), b), PaneLayout.single(pane(0), listOf(a, b, a), c).root)
    }
}
