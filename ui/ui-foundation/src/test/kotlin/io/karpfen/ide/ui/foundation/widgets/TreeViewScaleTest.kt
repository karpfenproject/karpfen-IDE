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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.setThemedContent
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestReporter
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The [TreeView] is lazy. A large expanded tree composes only the rows near the viewport, and flattening it stays
 * cheap. Laziness is checked by counting composed rows. The time budgets are asserted with 5x slack, and the measured
 * values are written to the test report. These budget tests are tagged `budget` and excluded from every test run,
 * because machine load and sleep cycles make wall-clock limits unreliable.
 */
@OptIn(ExperimentalTestApi::class)
class TreeViewScaleTest {

    /** 100 folders with 100 files each, all expanded: 10,100 visible rows. */
    private val bigTree: List<TreeNode<String>> = List(FOLDERS) { folder ->
        TreeNode("d$folder", "folder $folder", children = List(FILES) { file -> TreeNode("d$folder/f$file", "file $file.kstates") })
    }
    private val allExpanded: Set<String> = bigTree.map { it.key }.toSet()

    private val rowTag = SemanticsMatcher("is a tree row") { node ->
        node.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith("TreeView.row:") == true
    }

    @Test
    fun `a tree of ten thousand rows composes only the rows around the viewport`(reporter: TestReporter) = runComposeUiTest {
        var selected by mutableStateOf<String?>(null)
        setBigTree(selected = { selected }, onSelect = { selected = it })
        waitForIdle()

        val composed = onAllNodes(rowTag).fetchSemanticsNodes().size
        reporter.publishEntry("composedRows", "$composed of ${FOLDERS * (FILES + 1)} (viewport: $VISIBLE_ROWS)")
        assertTrue(composed <= VISIBLE_ROWS * 2, "only rows near the viewport are composed: $composed")

        selected = "d99/f99"
        waitForIdle()
        assertTrue(onAllNodes(rowTag).fetchSemanticsNodes().size <= VISIBLE_ROWS * 2, "still lazy after scrolling to the end")
    }

    @Test
    @Tag("budget")
    fun `the first frame and revealing the last row stay within budget`(reporter: TestReporter) = runComposeUiTest {
        var selected by mutableStateOf<String?>(null)
        val started = TimeSource.Monotonic.markNow()
        setBigTree(selected = { selected }, onSelect = { selected = it })
        waitForIdle()
        val firstFrame = started.elapsedNow()
        reporter.publishEntry("firstFrameMillis", firstFrame.inWholeMilliseconds.toString())
        assertWithinBudget("first frame", firstFrame, FIRST_FRAME_BUDGET)

        val revealStarted = TimeSource.Monotonic.markNow()
        selected = "d99/f99"
        waitForIdle()
        val reveal = revealStarted.elapsedNow()
        reporter.publishEntry("revealLastRowMillis", reveal.inWholeMilliseconds.toString())
        assertWithinBudget("reveal", reveal, REVEAL_BUDGET)
    }

    private fun ComposeUiTest.setBigTree(selected: () -> String?, onSelect: (String) -> Unit) {
        setThemedContent {
            Box(Modifier.size(300.dp, TREE_HEIGHT.dp)) {
                TreeView(
                    roots = bigTree,
                    expanded = allExpanded,
                    selected = selected(),
                    onToggleExpanded = {},
                    onSelect = onSelect,
                    onOpen = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Test
    @Tag("budget")
    fun `flattening ten thousand expanded rows is cheap`(reporter: TestReporter) {
        repeat(WARM_UP_RUNS) { TreeRows.flatten(bigTree, allExpanded) }
        val runs = List(MEASURED_RUNS) {
            val started = TimeSource.Monotonic.markNow()
            TreeRows.flatten(bigTree, allExpanded)
            started.elapsedNow()
        }
        val median = runs.sorted()[runs.size / 2]

        reporter.publishEntry("flattenMedianMicros", median.inWholeMicroseconds.toString())
        assertWithinBudget("flatten", median, FLATTEN_BUDGET)
    }

    private fun assertWithinBudget(what: String, measured: Duration, budget: Duration) {
        assertTrue(measured <= budget * SLACK, "$what took $measured; budget $budget (x$SLACK slack)")
    }

    /** The size of the big tree, the measured runs and the time budgets. */
    private companion object {
        const val FOLDERS = 100
        const val FILES = 100
        const val SLACK = 5
        const val TREE_HEIGHT = 440
        val VISIBLE_ROWS: Int = TREE_HEIGHT / TreeViewDefaults.RowHeight.value.toInt()
        const val WARM_UP_RUNS = 20
        const val MEASURED_RUNS = 21
        val FLATTEN_BUDGET = 5.milliseconds
        val FIRST_FRAME_BUDGET = 1_000.milliseconds
        val REVEAL_BUDGET = 200.milliseconds
    }
}
