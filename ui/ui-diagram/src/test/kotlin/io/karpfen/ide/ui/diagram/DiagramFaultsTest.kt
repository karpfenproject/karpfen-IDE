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
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.notation.diagram.layout.DiagramArrangement
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.StructureArrangement
import io.karpfen.ide.notation.diagram.layout.StructureLayout
import io.karpfen.ide.notation.diagram.layout.TextMeasurer
import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import io.karpfen.ide.notation.sync.DiagramProjection
import io.karpfen.ide.notation.sync.DiagramState
import io.karpfen.ide.notation.sync.DiagramTracker
import io.karpfen.ide.ui.foundation.theme.KarpfenColors
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests that a failure inside the diagram view stays inside it. Each test breaks one part of a notation on purpose,
 * the layout, the painter or a gesture, and checks that the view is still there, still reacts, and says what failed.
 */
@OptIn(ExperimentalTestApi::class)
class DiagramFaultsTest {

    @Test
    fun `a guarded step that fails gives its fallback and leaves a note, and only the first failure is shown`() {
        val faults = DiagramFaults()
        assertEquals(1, faults.guard("counting", { 0 }) { 1 })
        assertNull(faults.note)

        assertEquals(0, faults.guard("counting", { 0 }) { error("lost count") })
        faults.attempt("drawing") { throw IllegalArgumentException("no ink") }

        val note = faults.note.orEmpty()
        assertTrue("counting" in note && "lost count" in note, note)
        assertTrue("no ink" !in note, note)
    }

    @Test
    fun `a stack overflow is a failure like any other, and a cancellation is passed on`() {
        val faults = DiagramFaults()
        fun endless(depth: Int): Int = endless(depth + 1) + 1

        assertEquals(-1, faults.guard("recursing", { -1 }) { endless(0) })
        assertTrue("StackOverflowError" in faults.note.orEmpty())
        assertFailsWith<CancellationException> { faults.guard("waiting", { 0 }) { throw CancellationException("stop") } }
    }

    @Test
    fun `when the automatic layout fails, the elements are placed in a row and a note says so`() = runComposeUiTest {
        val broken = object : DiagramArrangement<StructureDiagram, StructureLayout> by StructureArrangement {
            override fun automatic(diagram: StructureDiagram, text: TextMeasurer): StructureLayout = error("no layout today")
        }
        show(notationWith(arrangement = broken))
        awaitNote()

        onNodeWithTag(DiagramViewTestTags.ZOOM).assertIsDisplayed()
        onNodeWithTag(DiagramViewTestTags.FAULT).assertTextContains("no layout today", substring = true)
    }

    @Test
    fun `when no layout can be made at all, the view stays and says so`() = runComposeUiTest {
        val broken = object : DiagramArrangement<StructureDiagram, StructureLayout> by StructureArrangement {
            override fun automatic(diagram: StructureDiagram, text: TextMeasurer): StructureLayout = error("no layout today")
            override fun byHand(diagram: StructureDiagram, vis: VisLayout, text: TextMeasurer): StructureLayout = error("no hands either")
        }
        show(notationWith(arrangement = broken))
        awaitNote()

        onNodeWithTag(DiagramViewTestTags.CANVAS).assertIsDisplayed()
        onNodeWithTag(DiagramViewTestTags.FAULT).assertTextContains("no layout today", substring = true)
        assertEquals(0, onAllNodesWithTag(DiagramViewTestTags.ZOOM).fetchSemanticsNodes().size)
    }

    @Test
    fun `when the painter fails, the view stays and still reacts`() = runComposeUiTest {
        val broken = object : DiagramPainter<StructureDiagram, StructureLayout> by StructurePainter {
            override fun paint(
                scope: DrawScope,
                diagram: StructureDiagram,
                layout: StructureLayout,
                texts: DiagramTexts,
                colors: KarpfenColors,
                faults: DiagramFaults,
            ): Unit = throw IllegalStateException("no ink")
        }
        show(notationWith(painter = broken))
        awaitNote()

        onNodeWithTag(DiagramViewTestTags.FAULT).assertTextContains("no ink", substring = true)
        onNodeWithTag(DiagramViewTestTags.ZOOM_IN).performClick()
        waitForIdle()
        onNodeWithTag(DiagramViewTestTags.ZOOM).assertTextContains("110%")
    }

    @Test
    fun `when a gesture fails, the view stays and the layout is as before`() = runComposeUiTest {
        val saved = ArrayList<VisLayout>()
        val broken = object : DiagramArrangement<StructureDiagram, StructureLayout> by StructureArrangement {
            override fun moveBox(diagram: StructureDiagram, vis: VisLayout, box: String, delta: DiagramPoint): VisLayout = error("stuck")
            override fun lines(diagram: StructureDiagram): Set<String> = error("no lines")
        }
        val notation = notationWith(arrangement = broken)
        val editing = DiagramEditing(notation.arrangement) { saved += it }
        show(notation, editing)
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(DiagramViewTestTags.ZOOM).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(DiagramViewTestTags.CANVAS).performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(80f, 40f)) }
        onNodeWithTag(DiagramViewTestTags.CANVAS).performMouseInput { rightClick(center) }
        onNodeWithTag(DiagramViewTestTags.CANVAS).performTouchInput { click(center) }
        awaitNote()

        assertNull(editing.vis, "the failed drag is given up")
        assertEquals(false, editing.isDragging)
        assertEquals(emptyList(), saved)
        onNodeWithTag(DiagramViewTestTags.ZOOM).assertIsDisplayed()
    }

    @Test
    fun `when the projection fails, the last diagram stays and the view says so`() = runComposeUiTest {
        var fail = false
        val tracker = DiagramTracker(DiagramProjection { text, outline -> if (fail) error("cannot read") else DiagramProjection.TYPES.of(text, outline) })
        val parser = defaultLanguageRegistry().support(KarpfenLanguage.KMETA).parser
        val good = tracker.update(TextSnapshot(TYPE, 0), parser.parse(TextSnapshot(TYPE, 0)))
        fail = true
        val bad = tracker.update(TextSnapshot(TYPE + " ", 1), parser.parse(TextSnapshot(TYPE + " ", 1)))

        assertEquals(good.diagram, bad.diagram)
        assertTrue("cannot read" in bad.problem.orEmpty(), bad.problem)

        show(DiagramNotation.TYPES, state = bad)
        awaitNote()
        onNodeWithTag(DiagramViewTestTags.FAULT).assertTextContains("cannot read", substring = true)
        onNodeWithTag(DiagramViewTestTags.CANVAS).assertIsDisplayed()
    }

    /** The notation of class diagrams with one part replaced. */
    private fun notationWith(
        arrangement: DiagramArrangement<StructureDiagram, StructureLayout> = StructureArrangement,
        painter: DiagramPainter<StructureDiagram, StructureLayout> = StructurePainter,
    ): DiagramNotation<StructureDiagram, StructureLayout> = DiagramNotation(DiagramProjection.TYPES, arrangement, painter, "nothing")

    private fun ComposeUiTest.show(
        notation: DiagramNotation<StructureDiagram, StructureLayout>,
        editing: DiagramEditing<StructureDiagram, StructureLayout> = DiagramEditing(notation.arrangement) { },
        state: DiagramState<StructureDiagram> = stateOf(TYPE),
    ) {
        setContent { KarpfenTheme(darkTheme = false) { Box(Modifier.size(600.dp, 400.dp)) { DiagramView(notation, state, editing) } } }
        waitForIdle()
    }

    /** Waits until the view shows the note of a failure. */
    private fun ComposeUiTest.awaitNote() {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(DiagramViewTestTags.FAULT).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
    }

    private fun stateOf(text: String): DiagramState<StructureDiagram> {
        val snapshot = TextSnapshot(text, 0)
        return DiagramTracker(DiagramProjection.TYPES).update(snapshot, defaultLanguageRegistry().support(KarpfenLanguage.KMETA).parser.parse(snapshot))
    }

    private companion object {
        /** One type that has itself, so the diagram has a box in the middle and a line. */
        val TYPE = """
            type "Node" "A node of a tree" {
                prop("name", "string")
                has("children", list("Node"))
            }
        """.trimIndent()
    }
}
