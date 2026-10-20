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

/** [OutlineState] and [OutlineProjection]: parse choice, staleness, versioned locations and the caret highlight. */
@OptIn(ExperimentalCoroutinesApi::class)
class OutlineStateTest {

    private val path: Path = Path.of("/ws/robot.kstates").toAbsolutePath()
    private val machine = Examples.file("karpfen-runtime/single_robot_example/cleaning_robot.kstates").text

    private fun scenario(block: suspend TestScope.(OutlineState, FakeOpenDocument, () -> io.karpfen.ide.ui.editor.state.CodeEditorState) -> Unit) =
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
