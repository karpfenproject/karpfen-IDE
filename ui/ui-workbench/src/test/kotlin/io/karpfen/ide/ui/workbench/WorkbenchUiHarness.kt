package io.karpfen.ide.ui.workbench

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.widgets.TreeViewTestTags
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestDispatcher
import java.nio.file.Path
import kotlin.coroutines.ContinuationInterceptor

/**
 * A composed [Workbench] over a [TestWorkbench] for headless UI tests. The workbench state runs on the virtual time of
 * the test body's scheduler, which Compose's frame clock does not advance: [settle] runs the state's work and then
 * lets Compose catch up, a few times, so intents started by clicks and the recompositions they cause both complete.
 */
@OptIn(ExperimentalTestApi::class)
class WorkbenchUi(val test: ComposeUiTest, val workbench: TestWorkbench) {

    /** Runs the state's pending work and the resulting frames. */
    fun settle() {
        repeat(4) {
            workbench.settle()
            test.waitForIdle()
        }
    }

    /** The project-tree row of [path]. */
    fun treeRow(path: Path): SemanticsNodeInteraction = test.onNodeWithTag(TreeViewTestTags.row(path.toAbsolutePath().normalize()))

    /** The node tagged [tag]. */
    fun tagged(tag: String): SemanticsNodeInteraction = test.onNodeWithTag(tag)
}

/** Runs [block] with a composed workbench (1280 x 860 dp) whose settings live below [directory]. */
@OptIn(ExperimentalTestApi::class)
fun workbenchUiTest(directory: Path, block: suspend WorkbenchUi.() -> Unit) = runComposeUiTest {
    val scheduler = (currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher).scheduler
    val workbench = TestWorkbench(scheduler, directory.resolve("settings"))
    setContent {
        Box(Modifier.size(1280.dp, 860.dp)) { Workbench(workbench.state) }
    }
    try {
        WorkbenchUi(this, workbench).block()
    } finally {
        workbench.close()
    }
}
