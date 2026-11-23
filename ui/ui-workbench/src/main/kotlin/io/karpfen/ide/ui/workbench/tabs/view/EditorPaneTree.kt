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
package io.karpfen.ide.ui.workbench.tabs.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.karpfen.ide.ui.foundation.layout.ShareSplitPane
import io.karpfen.ide.ui.foundation.layout.SplitOrientation as DividerOrientation
import io.karpfen.ide.ui.workbench.tabs.PaneId
import io.karpfen.ide.ui.workbench.tabs.PaneLayout
import io.karpfen.ide.ui.workbench.tabs.PaneNode
import io.karpfen.ide.ui.workbench.tabs.SplitId
import io.karpfen.ide.ui.workbench.tabs.SplitOrientation

/**
 * The editor area as the tree of panes of [layout]. Every split is a `ShareSplitPane` that gives its first part the
 * split's share and its second part the rest, side by side or one above the other. A drag of the border between them
 * reports the new share with the split's id to [onResize]. Every pane is drawn by [pane] with its id.
 *
 * The content of each pane is a movable content that the tree keeps by the pane's id. When a pane is split or a pane
 * next to it closes, the pane moves to another place in the tree, and its composition moves with it instead of
 * starting afresh. So everything a pane's composables remember survives, such as the zoom and the position of its
 * diagram, the border between its text and its diagram, and an open popup. The content of a pane that left the layout
 * is dropped.
 *
 * The tree carries the test tag `EditorAreaTestTags.AREA` and every split the tag `EditorAreaTestTags.split` of its id.
 */
@Composable
internal fun EditorPaneTree(
    layout: PaneLayout,
    onResize: (SplitId, Float) -> Unit,
    modifier: Modifier = Modifier,
    pane: @Composable (PaneId) -> Unit,
) {
    val current by rememberUpdatedState(pane)
    val contents = remember { HashMap<PaneId, @Composable () -> Unit>() }
    val shown = layout.panes.mapTo(HashSet()) { it.id }
    contents.keys.retainAll(shown)
    val contentOf: (PaneId) -> @Composable () -> Unit = { id -> contents.getOrPut(id) { movableContentOf { current(id) } } }
    Box(modifier.fillMaxSize().testTag(EditorAreaTestTags.AREA)) {
        PaneTreeNode(layout.root, onResize, contentOf)
    }
}

/** The part of the tree at [node]: a pane's content from [contentOf], or a split of the parts below it. */
@Composable
private fun PaneTreeNode(node: PaneNode, onResize: (SplitId, Float) -> Unit, contentOf: (PaneId) -> @Composable () -> Unit) {
    when (node) {
        is PaneNode.Pane -> contentOf(node.id)()
        is PaneNode.Split -> ShareSplitPane(
            orientation = if (node.orientation == SplitOrientation.ROW) DividerOrientation.HORIZONTAL else DividerOrientation.VERTICAL,
            share = node.share,
            onShareChange = { onResize(node.id, it) },
            modifier = Modifier.fillMaxSize().testTag(EditorAreaTestTags.split(node.id.value)),
            minShare = PaneLayout.MIN_SHARE,
            maxShare = PaneLayout.MAX_SHARE,
            first = { PaneTreeNode(node.first, onResize, contentOf) },
            second = { PaneTreeNode(node.second, onResize, contentOf) },
        )
    }
}
