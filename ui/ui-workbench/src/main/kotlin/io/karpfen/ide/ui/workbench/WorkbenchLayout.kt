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
package io.karpfen.ide.ui.workbench

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.layout.SplitOrientation
import io.karpfen.ide.ui.foundation.layout.SplitPane
import io.karpfen.ide.ui.foundation.layout.SplitSide
import io.karpfen.ide.ui.foundation.layout.ToolWindow
import io.karpfen.ide.ui.workbench.layout.LayoutState
import io.karpfen.ide.ui.workbench.layout.LayoutUiState
import io.karpfen.ide.ui.workbench.layout.ToolWindowId

/**
 * Arranges the tool windows around the editor area. Project sits on the left as the first pane of its split. Outline
 * sits on the right. Problems sits at the bottom, the Language Tools Output below Problems and the Runtime below the
 * output. These four are the second pane of their splits. The three bottom tool windows take the whole width of the
 * window, so Project and Outline end above them and only the editor row gets shorter when a bottom tool window opens.
 *
 * Each tool window lives in a size-based `SplitPane`. Its fixed size is the tool window's persisted size in dp, and a
 * drag of the divider stores the new size through `LayoutState.resizeToolWindow`. The workbench therefore never
 * converts between split fractions and persisted sizes. A hidden tool window takes no space. The editor area keeps its
 * place in the composition while tool windows are shown and hidden.
 *
 * The layout reports its own height to `LayoutState.reportAvailableHeight`, so a tool window can be given a share of
 * it, as the Runtime tool window is when a run starts.
 */
@Composable
internal fun WorkbenchLayout(
    layout: LayoutUiState,
    layoutState: LayoutState,
    panes: WorkbenchPanes,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measured = modifier.onSizeChanged { size -> layoutState.reportAvailableHeight(with(density) { size.height.toDp().value }) }
    ToolWindowSplit(ToolWindowId.RUNTIME, SplitOrientation.VERTICAL, SplitSide.SECOND, layout, layoutState, panes.runtime, measured) {
        ToolWindowSplit(ToolWindowId.OUTPUT, SplitOrientation.VERTICAL, SplitSide.SECOND, layout, layoutState, panes.output) {
            ToolWindowSplit(ToolWindowId.PROBLEMS, SplitOrientation.VERTICAL, SplitSide.SECOND, layout, layoutState, panes.problems) {
                ToolWindowSplit(ToolWindowId.PROJECT, SplitOrientation.HORIZONTAL, SplitSide.FIRST, layout, layoutState, panes.project) {
                    ToolWindowSplit(
                        ToolWindowId.OUTLINE, SplitOrientation.HORIZONTAL, SplitSide.SECOND, layout, layoutState, panes.outline,
                        main = panes.editor,
                    )
                }
            }
        }
    }
}

/** The contents of the editor area and of each tool window that [WorkbenchLayout] arranges. */
internal class WorkbenchPanes(
    val project: @Composable () -> Unit,
    val editor: @Composable () -> Unit,
    val outline: @Composable () -> Unit,
    val problems: @Composable () -> Unit,
    val output: @Composable () -> Unit,
    val runtime: @Composable () -> Unit,
)

/**
 * Shows [main] with the tool window [id] on its [side] while that tool window is visible, and [main] alone otherwise.
 * The split exists in both cases. [main] therefore keeps its place in the composition, and showing or hiding the tool
 * window keeps the state of everything inside [main], such as the editor's keyboard focus.
 */
@Composable
private fun ToolWindowSplit(
    id: ToolWindowId,
    orientation: SplitOrientation,
    side: SplitSide,
    layout: LayoutUiState,
    layoutState: LayoutState,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    main: @Composable () -> Unit,
) {
    val toolWindow = layout.toolWindow(id)
    val tool: @Composable () -> Unit = {
        ToolWindow(title = id.title, modifier = Modifier.fillMaxSize().testTag(WorkbenchTestTags.toolWindow(id))) { content() }
    }
    SplitPane(
        orientation = orientation,
        fixedSide = side,
        fixedSize = toolWindow.size.dp,
        onFixedSizeChange = { layoutState.resizeToolWindow(id, it.value) },
        modifier = modifier.fillMaxSize(),
        fixedPaneShown = toolWindow.visible,
        first = if (side == SplitSide.FIRST) tool else main,
        second = if (side == SplitSide.FIRST) main else tool,
    )
}
