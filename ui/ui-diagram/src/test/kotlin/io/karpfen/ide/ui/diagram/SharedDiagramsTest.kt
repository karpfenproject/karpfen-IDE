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
package io.karpfen.ide.ui.diagram

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.session.DocumentId
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.notation.sync.VisFiles
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two diagram panes of one file that are given the same [SharedDiagrams] show one diagram: a box dropped in one pane
 * stands at its new place in the other, the companion file is written once per drop, and the share goes away with the
 * last pane that shows it.
 */
@OptIn(ExperimentalTestApi::class)
class SharedDiagramsTest {

    @TempDir
    lateinit var dir: Path

    private val machine: Path get() = dir.resolve("single.kstates")

    /** What the panes under test hold: the shares, the count of finished writes, and the panes that show. */
    private class Harness {
        lateinit var shared: SharedDiagrams
        var saves by mutableStateOf(0)
        var shown by mutableStateOf(setOf(LEFT, RIGHT))
    }

    /** Shows the statechart of [MACHINE] in a left and a right pane of 400 by 300 dp each, sharing one [SharedDiagrams]. */
    private fun ComposeUiTest.showTwoPanes(harness: Harness) {
        setContent {
            KarpfenTheme(darkTheme = false) {
                val scope = rememberCoroutineScope()
                val session = remember {
                    DocumentSession(
                        id = DocumentId.of(machine),
                        support = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES),
                        text = EditableText(MACHINE),
                        scope = scope,
                        config = DocumentSessionConfig(tokenizeDispatcher = Dispatchers.Unconfined, parseDispatcher = Dispatchers.Unconfined),
                    )
                }
                val shared = rememberSharedDiagrams().also { harness.shared = it }
                Row {
                    listOf(LEFT, RIGHT).forEach { side ->
                        if (side in harness.shown) {
                            Box(Modifier.size(400.dp, 300.dp).testTag(side)) {
                                DiagramPane(
                                    session = session,
                                    modelFile = machine,
                                    kind = DiagramKind.STATECHART,
                                    modifier = Modifier.fillMaxSize(),
                                    onLayoutSaved = { harness.saves++ },
                                    shared = shared,
                                )
                            }
                        }
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(DiagramViewTestTags.ZOOM).fetchSemanticsNodes().size == 2 }
        waitForIdle()
    }

    private fun ComposeUiTest.canvas(side: String): SemanticsNodeInteraction =
        onNode(hasTestTag(DiagramViewTestTags.CANVAS) and hasAnyAncestor(hasTestTag(side)))

    /** Drags in the canvas of [side] from [start], relative to its centre, down by [distance] dp in small steps. */
    private fun ComposeUiTest.dragDown(side: String, start: Float, distance: Int) {
        val step = with(density) { 10.dp.toPx() }
        val offset = with(density) { start.dp.toPx() }
        canvas(side).performMouseInput {
            moveTo(center + Offset(0f, offset))
            press()
            repeat(distance / 10) { moveBy(Offset(0f, step)) }
            release()
        }
        waitForIdle()
    }

    @Test
    fun `a box dropped in one pane stands at its new place in the other, and each drop is written once`() = runComposeUiTest {
        val harness = Harness()
        showTwoPanes(harness)

        dragDown(LEFT, start = 0f, distance = 80)
        waitUntil(timeoutMillis = 10_000) { harness.saves >= 1 }
        waitForIdle()
        assertEquals(1, harness.saves, "the drop is written once, not once per pane")
        val first = VisFiles.read(VisFiles.pathFor(machine))

        dragDown(RIGHT, start = 80f, distance = 40)
        waitUntil(timeoutMillis = 10_000) { harness.saves >= 2 }
        waitForIdle()

        assertEquals(2, harness.saves, "the right pane grabbed the box where the left pane had dropped it")
        val second = VisFiles.read(VisFiles.pathFor(machine))
        assertTrue(first != null && second != null && first != second, "the second drop moved the box again: $first, $second")
        assertEquals(1, harness.shared.size, "one share for the file")
    }

    @Test
    fun `the share stays while one pane shows the diagram and goes with the last`() = runComposeUiTest {
        val harness = Harness()
        showTwoPanes(harness)
        assertEquals(1, harness.shared.size)

        harness.shown = setOf(LEFT)
        waitForIdle()
        assertEquals(1, harness.shared.size, "the left pane still shows the diagram")

        dragDown(LEFT, start = 0f, distance = 60)
        waitUntil(timeoutMillis = 10_000) { harness.saves >= 1 }
        assertTrue(Files.exists(VisFiles.pathFor(machine)), "the left pane still writes the layout")

        harness.shown = emptySet()
        waitForIdle()
        assertEquals(0, harness.shared.size, "no pane shows the diagram any more")
    }

    @Test
    fun `a drag cut off by its pane leaving the screen is given up, and the other pane shows the box where it was`() =
        runComposeUiTest {
            val harness = Harness()
            showTwoPanes(harness)
            val step = with(density) { 10.dp.toPx() }
            canvas(RIGHT).performMouseInput {
                moveTo(center)
                press()
                repeat(8) { moveBy(Offset(0f, step)) }
            }
            waitForIdle()

            harness.shown = setOf(LEFT)
            waitForIdle()
            onNode(hasTestTag(LEFT)).performMouseInput { release() }
            waitForIdle()
            assertEquals(0, harness.saves, "the cut-off drag wrote nothing")

            dragDown(LEFT, start = 0f, distance = 40)
            waitUntil(timeoutMillis = 10_000) { harness.saves >= 1 }
            assertEquals(1, harness.saves, "the left pane grabbed the box at its place from before the cut-off drag")
        }

    private companion object {
        /** The tag of the left pane. */
        const val LEFT = "left"

        /** The tag of the right pane. */
        const val RIGHT = "right"

        /** A state machine with one state, which sits in the middle of the fitted drawing. */
        val MACHINE = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    STATE "alone" { }
                }
                TRANSITIONS { }
            }
        """.trimIndent()
    }
}
