package io.karpfen.ide.ui.workbench.e2e

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * An environment descriptor edited in the IDE over the production wiring ([ProductionWorkbench]), as a user does it:
 * open it from the project tree into an editor tab, edit it (the workspace reports what the unsaved text names
 * wrongly), complete a file path and an attached object, and save; and type values by hand that completion leaves as
 * they are.
 */
class DescriptorEditingEndToEndTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()
    private val robot: Path get() = root.resolve(DescriptorExamples.CONTEXT_DIRECTORY)
    private val descriptor: Path get() = robot.resolve(DescriptorExamples.VALID)

    private fun ProductionWorkbench.textOf(editor: CodeEditorState): String = editor.session.text.snapshot.value.text

    /** Selects [old], the first occurrence in the editor's text, and types [new] over it. */
    private fun ProductionWorkbench.replace(editor: CodeEditorState, old: String, new: String) {
        val start = textOf(editor).indexOf(old)
        require(start >= 0) { "'$old' not in the descriptor" }
        editor.caret.select(start, start + old.length)
        editor.type(new)
        settle()
    }

    /** Opens completion at the caret and accepts its first item, which must be [expected]. */
    private fun ProductionWorkbench.completeWith(editor: CodeEditorState, expected: String) {
        editor.execute(EditorActionIds.COMPLETION)
        settle()
        val completion = assertNotNull(editor.completion.state.value, "completion opened")
        assertEquals(expected, completion.selected.label, "${completion.items.map { it.label }}")
        assertTrue(editor.completion.accept())
        settle()
    }

    /**
     * Selects the value after [key] (`"key": ` up to the next `,`), types [value] over it one character at a time, as a
     * user does, and presses Enter.
     */
    private fun ProductionWorkbench.typeValueAndEnter(editor: CodeEditorState, key: String, value: String) {
        val text = textOf(editor)
        val start = text.indexOf("\"$key\": ") + "\"$key\": ".length
        require(start >= "\"$key\": ".length) { "'$key' not in the descriptor" }
        editor.caret.select(start, text.indexOf(',', start))
        value.forEach { character ->
            editor.type(character.toString())
            settle()
        }
        editor.handleKey(KeyStroke(KeyCode.ENTER))
        settle()
    }

    @Test
    fun `a number or null typed as a value and ended with Enter stays as it was typed`() = runTest(timeout = 120.seconds) {
        Examples.copyTo(root)
        Files.writeString(descriptor, DescriptorExamples.text(DescriptorExamples.VALID))
        ProductionWorkbench(testScheduler, dir.resolve("settings")).use { workbench ->
            with(workbench) {
                open(root)
                val editor = openEditor(descriptor)

                typeValueAndEnter(editor, "tickDelayMs", "10")
                typeValueAndEnter(editor, "eventTtlMs", "30")
                typeValueAndEnter(editor, "events", "null")

                val text = textOf(editor)
                assertTrue(text.contains("\"tickDelayMs\": 10\n"), "not the typical 100:\n$text")
                assertTrue(text.contains("\"eventTtlMs\": 30\n"), "not the typical 3000:\n$text")
                assertTrue(text.contains("\"events\": null\n"), "not a file path:\n$text")
            }
        }
    }

    @Test
    fun `a descriptor opens from the project tree, completes file paths and objects, and saves`() = runTest(timeout = 120.seconds) {
        Examples.copyTo(root)
        Files.writeString(descriptor, DescriptorExamples.text(DescriptorExamples.VALID))
        ProductionWorkbench(testScheduler, dir.resolve("settings")).use { workbench ->
            with(workbench) {
                open(root)

                state.openNode(descriptor).joinSettled()

                assertEquals(descriptor, state.tabs.active.value, "the descriptor opens in an editor tab")
                assertEquals(emptyList(), fileOperations.openedExternally)
                val editor = assertNotNull(state.tabs.editorFor(descriptor))
                assertEquals(KarpfenLanguage.ENVIRONMENT, editor.session.language)
                assertTrue(state.outline.state.value.nodes.any { it.name == "stateMachines" }, "the outline lists its keys")

                replace(editor, "cleaning_robot.kmodel", "c")
                val missing = workspace.problems.value.of(descriptor)?.diagnostics.orEmpty()
                assertEquals(listOf(WorkspaceDiagnosticCodes.DESCRIPTOR_MISSING_FILE), missing.map { it.code }, "the unsaved text names 'c'")
                assertTrue(document(descriptor).isDirty.value)

                completeWith(editor, "cleaning_robot.kmodel")
                assertTrue(textOf(editor).contains("\"model\": \"cleaning_robot.kmodel\""), textOf(editor))
                assertEquals(null, workspace.problems.value.of(descriptor), "the completed path names the model again")

                replace(editor, "turtle", "t")
                completeWith(editor, "turtle")
                assertTrue(textOf(editor).contains("\"attachedTo\": \"turtle\""), textOf(editor))

                replace(editor, "\"tickDelayMs\": 50", "\"tickDelayMs\": 100")
                state.saveActive().joinSettled()

                assertFalse(document(descriptor).isDirty.value)
                assertEquals(textOf(editor), Files.readString(descriptor))
                assertTrue(Files.readString(descriptor).contains("\"tickDelayMs\": 100"))
                assertEquals(null, workspace.problems.value.of(descriptor))
            }
        }
    }
}
