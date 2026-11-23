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
package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.ui.workbench.settings.StoredPanes
import io.karpfen.ide.ui.workbench.tabs.PaneId
import io.karpfen.ide.ui.workbench.tabs.PaneLayout
import io.karpfen.ide.ui.workbench.tabs.PaneNode
import io.karpfen.ide.ui.workbench.tabs.SplitId
import io.karpfen.ide.ui.workbench.tabs.SplitOrientation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests the conversion between the live arrangement of the editor panes and their stored form in the project file. */
class StoredPaneLayoutsTest {

    private val root: Path = Path.of("/work/project").toAbsolutePath().normalize()
    private val a: Path = root.resolve("a.kstates")
    private val b: Path = root.resolve("sub/b.kmeta")
    private val outside: Path = root.resolveSibling("elsewhere.kmodel")

    private val stored = StoredPanes(
        StoredPanes.Node.Split(
            StoredPanes.Orientation.COLUMN,
            0.25f,
            StoredPanes.Node.Pane(listOf("a.kstates", "sub/b.kmeta"), "a.kstates"),
            StoredPanes.Node.Pane(listOf("sub/b.kmeta"), "sub/b.kmeta"),
        ),
        activePane = 1,
    )

    @Test
    fun `a stored tree becomes a layout with ids from the first id, panes before splits, and back`() {
        val layout = StoredPaneLayouts.restore(stored, root, firstId = 7)

        assertEquals(
            PaneLayout(
                PaneNode.Split(SplitId(9), SplitOrientation.COLUMN, 0.25f, PaneNode.Pane(PaneId(7), listOf(a, b), a), PaneNode.Pane(PaneId(8), listOf(b), b)),
                PaneId(8),
            ),
            layout,
        )
        assertEquals(stored, StoredPaneLayouts.store(layout, root))
    }

    @Test
    fun `one stored pane keeps the first id`() {
        val layout = StoredPaneLayouts.restore(StoredPanes.single(listOf("a.kstates")), root, firstId = 3)

        assertEquals(PaneLayout.single(PaneId(3), listOf(a), a), layout, "a pane without an active tab has its last tab active")
    }

    @Test
    fun `files outside the project folder are left out and a pane without files is not stored`() {
        val layout = PaneLayout(
            PaneNode.Split(
                SplitId(3),
                SplitOrientation.ROW,
                0.5f,
                PaneNode.Pane(PaneId(1), listOf(outside), outside),
                PaneNode.Pane(PaneId(2), listOf(a, outside), outside),
            ),
            PaneId(1),
        )

        assertEquals(StoredPanes(StoredPanes.Node.Pane(listOf("a.kstates"))), StoredPaneLayouts.store(layout, root))
    }

    @Test
    fun `names that lead outside the project folder are left out and a share is kept within the bounds of a drag`() {
        val hand = StoredPanes(
            StoredPanes.Node.Split(
                StoredPanes.Orientation.ROW,
                0.01f,
                StoredPanes.Node.Pane(listOf("../elsewhere.kmodel", "a.kstates"), "../elsewhere.kmodel"),
                StoredPanes.Node.Pane(listOf("sub/../a.kstates")),
            ),
        )

        val layout = StoredPaneLayouts.restore(hand, root, firstId = 1)

        assertEquals(listOf(listOf(a), listOf(a)), layout.panes.map { it.tabs })
        assertEquals(listOf<Path?>(a, a), layout.panes.map { it.active })
        assertEquals(PaneLayout.MIN_SHARE, (layout.root as PaneNode.Split).share)
    }
}
