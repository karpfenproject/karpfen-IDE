package io.karpfen.ide.ui.workbench

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 * The tool windows around the editor area: Project left (the FIRST pane), Outline right, Problems at the bottom and the
 * Language Tools Output below Problems (SECOND panes), each in a size-based `SplitPane` whose fixed size is the tool
 * window's persisted size in dp (`fixedSize = size.dp`, `onFixedSizeChange = { resizeToolWindow(id, it.value) }`), so
 * the workbench never converts between split fractions and the persisted sizes. A hidden tool window takes no space.
 */
@Composable
internal fun WorkbenchLayout(
    layout: LayoutUiState,
    layoutState: LayoutState,
    panes: WorkbenchPanes,
    modifier: Modifier = Modifier,
) {
    ToolWindowSplit(ToolWindowId.PROJECT, SplitOrientation.HORIZONTAL, SplitSide.FIRST, layout, layoutState, panes.project, modifier) {
        ToolWindowSplit(ToolWindowId.OUTPUT, SplitOrientation.VERTICAL, SplitSide.SECOND, layout, layoutState, panes.output) {
            ToolWindowSplit(ToolWindowId.PROBLEMS, SplitOrientation.VERTICAL, SplitSide.SECOND, layout, layoutState, panes.problems) {
                ToolWindowSplit(
                    ToolWindowId.OUTLINE, SplitOrientation.HORIZONTAL, SplitSide.SECOND, layout, layoutState, panes.outline,
                    main = panes.editor,
                )
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
)

/** [main] with the tool window [id] on its [side] when visible, else [main] alone. */
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
    if (!toolWindow.visible) {
        main()
        return
    }
    val tool: @Composable () -> Unit = {
        ToolWindow(title = id.title, modifier = Modifier.fillMaxSize().testTag(WorkbenchTestTags.toolWindow(id))) { content() }
    }
    SplitPane(
        orientation = orientation,
        fixedSide = side,
        fixedSize = toolWindow.size.dp,
        onFixedSizeChange = { layoutState.resizeToolWindow(id, it.value) },
        modifier = modifier.fillMaxSize(),
        first = if (side == SplitSide.FIRST) tool else main,
        second = if (side == SplitSide.FIRST) main else tool,
    )
}
