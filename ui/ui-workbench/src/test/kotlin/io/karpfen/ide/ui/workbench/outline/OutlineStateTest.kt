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
package io.karpfen.ide.ui.workbench.outline

import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.Mutations
import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextLineage
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.workbench.testing.FakeOpenDocument
import io.karpfen.ide.ui.workbench.testing.editorOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [OutlineState] and [OutlineProjection]. They cover which parse is shown, staleness, versioned locations and
 * the caret highlight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutlineStateTest {

    private val path: Path = Path.of("/ws/robot.kstates").toAbsolutePath()
    private val machine = Examples.file("karpfen-runtime/single_robot_example/cleaning_robot.kstates").text

    private fun scenario(block: suspend TestScope.(OutlineState, FakeOpenDocument, () -> CodeEditorState) -> Unit) =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            val outline = OutlineState(scope)
            val document = FakeOpenDocument(path, machine, scope, dispatcher)
            val editor by lazy { editorOf(document, scope, dispatcher) }
            try {
                block(outline, document) { editor }
            } finally {
                scope.cancel()
            }
        }

    private fun List<OutlineNode>.flatten(): List<OutlineNode> = flatMap { listOf(it) + it.children.flatten() }

    private val kstatesParser = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES).parser

    /** An [EditableText] that counts in [steps] the version steps its ranges are mapped across. */
    private class CountingText(private val delegate: EditableText) : EditableText by delegate {
        var steps = 0L

        override fun mapRange(range: TextRange, fromVersion: Long, fromLineage: TextLineage, toVersion: Long): TextRange? {
            steps += (toVersion - fromVersion).coerceAtLeast(0)
            return delegate.mapRange(range, fromVersion, fromLineage, toVersion)
        }
    }

    @Test
    fun `the outline follows the bound editor's parse and highlights the nodes at the caret`() = scenario { outline, document, editor ->
        assertEquals(OutlineUiState(), outline.state.value)
        outline.bindEditor(editor())
        advanceUntilIdle()

        val state = outline.state.value
        assertEquals(path, state.path)
        assertEquals(0L, state.version)
        assertTrue(state.lineage === document.session.text.lineage)
        assertFalse(state.isStale)
        assertEquals(SymbolKind.STATEMACHINE, state.nodes.single().kind)

        val offset = machine.indexOf("STATE \"drive fast\"") + 8
        editor().caret.moveTo(offset)
        advanceUntilIdle()
        val names = outline.state.value.highlighted.map { it.name }
        assertEquals(listOf("drive", "drive fast"), names.takeLast(2))
        assertTrue(outline.state.value.highlighted.zipWithNext().all { (outer, inner) -> inner in outer.children })
    }

    @Test
    fun `while the text has syntax errors the last good parse is shown, stale, with its version`() = scenario { outline, document, editor ->
        outline.bindEditor(editor())
        advanceUntilIdle()
        document.session.text.apply(listOf(TextEdit.insert(0, "}}}\n")), EditOrigin.Typing)
        editor().caret.moveTo(machine.indexOf("STATE \"observe\"") + 4 + 8)
        advanceUntilIdle()

        val state = outline.state.value
        assertTrue(state.isStale)
        assertEquals(0L, state.version)
        val observe = state.nodes.flatten().first { it.name == "observe" }
        assertEquals(machine.indexOf("observe\" {"), state.locationOf(observe)?.range?.start, "ranges of the last good parse")
        assertEquals("observe", state.highlighted.last().name, "the highlight maps the old ranges to the caret's text")
    }

    @Test
    fun `binding null clears the outline and stops following`() = scenario { outline, document, editor ->
        outline.bindEditor(editor())
        advanceUntilIdle()
        outline.bindEditor(null)
        assertEquals(OutlineUiState(), outline.state.value)
        document.session.text.apply(listOf(TextEdit.insert(0, "// x\n")), EditOrigin.Typing)
        advanceUntilIdle()
        assertEquals(OutlineUiState(), outline.state.value)
    }

    @Test
    fun `while the last good parse lags, a projection maps the node ranges only across the versions since the previous one`() {
        val text = CountingText(EditableText(machine))
        val good = kstatesParser.parse(text.snapshot.value)
        val nodeCount = good.outline.flatten().size
        val projection = OutlineProjection(path, text)
        val caret = machine.indexOf("STATE \"drive fast\"") + 8
        projection.of(null, good, text.snapshot.value, caret)

        val costs = (1..300).map { typed ->
            text.apply(listOf(TextEdit.insert(0, "x")), EditOrigin.Typing)
            text.steps = 0
            val state = projection.of(null, good, text.snapshot.value, caret + typed)
            assertEquals(listOf("drive", "drive fast"), state.highlighted.map { it.name }.takeLast(2), "after $typed keystrokes")
            text.steps
        }

        assertTrue(costs.all { it <= 2L * nodeCount }, "one version step per node range at most, was up to ${costs.max()} for $nodeCount nodes")
        assertEquals(costs.first(), costs.last(), "the cost does not grow with the edits since the last good parse")
    }

    @Test
    fun `the highlight survives more edits since the last good parse than the text retains`() {
        val text = EditableText(machine, historyLimit = 10)
        val good = kstatesParser.parse(text.snapshot.value)
        val projection = OutlineProjection(path, text)
        val caret = machine.indexOf("STATE \"drive fast\"") + 8

        val names = (1..50).map { typed ->
            text.apply(listOf(TextEdit.insert(0, "x")), EditOrigin.Typing)
            projection.of(null, good, text.snapshot.value, caret + typed).highlighted.lastOrNull()?.name
        }

        assertEquals(List(50) { "drive fast" }, names)
    }

    @Test
    fun `the projection of no parse shows the path only`() {
        val projection = OutlineProjection(path, EditableText(""))
        val state = projection.of(null, null, EditableText("").snapshot.value, caret = 0)
        assertEquals(path, state.path)
        assertTrue(state.nodes.isEmpty())
        assertNull(state.version)
    }

    @Test
    fun `the projection never throws on mutated text and highlights a chain of nested nodes`() {
        val parser = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES).parser
        Mutations.random(machine, seed = 42, count = 300).forEachIndexed { index, mutation ->
            val mutated = mutation.text
            val text = EditableText(mutated)
            val snapshot = text.snapshot.value
            val parse = parser.parse(snapshot)
            val caret = (index * 37) % (mutated.length + 1)
            val state = OutlineProjection(path, text).of(parse, null, snapshot, caret)
            assertTrue(state.highlighted.zipWithNext().all { (outer, inner) -> inner in outer.children }, "mutation $index")
            assertTrue(state.highlighted.all { it.range.containsInclusive(caret) }, "mutation $index")
        }
    }
}
