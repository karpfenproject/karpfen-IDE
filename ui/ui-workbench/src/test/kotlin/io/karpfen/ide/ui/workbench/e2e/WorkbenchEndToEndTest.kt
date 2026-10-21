package io.karpfen.ide.ui.workbench.e2e

import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.core.workspace.document.ExternalChangeKind
import io.karpfen.ide.core.workspace.files.FileChangeKind
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.workbench.problems.ProblemItem
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The workbench over the production wiring (`defaultWorkbenchServices` with test values, see [ProductionWorkbench]) on
 * a copy of the examples, as a user drives it (INTEGRATION, plan §4): open the folder, edit, see syntax and dsl-tools
 * diagnostics in the session, the Problems view and the status bar, complete with the project's types, save and undo
 * with coalescing on the test time source, and external changes through the fake watcher.
 */
class WorkbenchEndToEndTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()
    private val robot: Path get() = root.resolve(DescriptorExamples.CONTEXT_DIRECTORY)
    private val machine: Path get() = robot.resolve("cleaning_robot.kstates")
    private val model: Path get() = robot.resolve("cleaning_robot.kmodel")

    private fun scenario(block: suspend ProductionWorkbench.() -> Unit) = runTest(timeout = 120.seconds) {
        Examples.copyTo(root)
        workbench().use { workbench ->
            workbench.open(root)
            workbench.block()
        }
    }

    private fun TestScope.workbench() = ProductionWorkbench(testScheduler, dir.resolve("settings"))

    @Test
    fun `the examples open without problems and the window title names the folder`() = scenario {
        assertEquals(0, state.problems.state.value.errorCount)
        assertEquals(0, state.problems.state.value.warningCount)
        assertEquals(emptyList(), state.problems.state.value.groups)
        assertEquals(0, state.statusBar.state.value.errorCount)
        assertEquals(Examples.contexts().size, workspace.analysis.value.contexts.size, "one implicit context per example directory")
        assertEquals("${root.fileName} — karpfen IDE", state.windowTitle.value)
    }

    @Test
    fun `a syntax error typed into an open file reaches Problems and the status bar, and undo clears it`() = scenario {
        val editor = openEditor(machine)
        editor.caret.moveTo(textOf(editor).indexOf("STATES {") + "STATES {".length)

        editor.type(" STATE")
        settle()

        val errors = itemsOf(machine)
        assertTrue(errors.isNotEmpty(), "the parser reported the incomplete state")
        assertTrue(errors.all { it.diagnostic.source == DiagnosticSource.PARSER }, "$errors")
        assertEquals(errors.size, state.problems.state.value.errorCount)
        assertEquals(state.problems.state.value.errorCount, state.statusBar.state.value.errorCount)
        assertEquals(errors.map { it.diagnostic }, document(machine).session.diagnostics.value.diagnostics)

        assertTrue(editor.execute(EditorActionIds.UNDO))
        settle()

        assertEquals(0, state.problems.state.value.errorCount)
        assertEquals(0, state.statusBar.state.value.errorCount)
        assertFalse(document(machine).isDirty.value, "undo returned to the save point")
    }

    @Test
    fun `a dsl-tools error of the project analysis is pushed into the open model and leaves with the fix`() = scenario {
        val editor = openEditor(model)
        val diameter = textOf(editor).indexOf("\"diameter\"") + 1
        editor.caret.select(diameter, diameter + "diameter".length)

        editor.type("radius")
        settle()

        val error = itemsOf(model).single()
        assertEquals(DiagnosticSource.DSL_TOOLS, error.diagnostic.source)
        assertEquals(error.diagnostic, document(model).session.diagnostics.value.diagnostics.single(), "pushed into the session")
        assertEquals(1, state.statusBar.state.value.errorCount)

        editor.execute(EditorActionIds.UNDO)
        settle()
        assertEquals(emptyList(), itemsOf(model))
        assertEquals(0, state.statusBar.state.value.errorCount)
    }

    @Test
    fun `completion offers the domain types of the context's metamodel from the project analysis`() = scenario {
        val editor = openEditor(machine)
        val attached = textOf(editor).indexOf("ATTACHED TO \"") + "ATTACHED TO \"".length
        editor.caret.moveTo(attached)

        editor.execute(EditorActionIds.COMPLETION)
        settle()

        val labels = assertNotNull(editor.completion.state.value, "completion opened").items.map { it.label }
        assertEquals(listOf("Obstacle", "Robot", "Room", "TwoDObject", "Vector", "Wall"), labels.sorted(), "no event types")
    }

    @Test
    fun `typing coalesces on the workspace's time source, save marks the save point and undo leaves it`() = scenario {
        val editor = openEditor(machine)
        val original = Files.readString(machine)
        editor.caret.moveTo(0)

        "abc".forEach { editor.type(it.toString()) }
        time += 2.seconds
        editor.type("d")
        settle()
        assertTrue(textOf(editor).startsWith("abcdSTATEMACHINE"))
        assertTrue(document(machine).isDirty.value)

        state.saveActive().joinSettled()
        assertFalse(document(machine).isDirty.value)
        assertEquals("abcd$original", Files.readString(machine))

        editor.execute(EditorActionIds.UNDO)
        settle()
        assertTrue(textOf(editor).startsWith("abcSTATEMACHINE"), "the late 'd' is a step of its own")
        assertTrue(document(machine).isDirty.value)
        editor.execute(EditorActionIds.UNDO)
        settle()
        assertEquals(original, textOf(editor), "'abc' typed within the coalescing window is one step")
        assertEquals("abcd$original", Files.readString(machine), "undo does not write")

        editor.execute(EditorActionIds.REDO)
        editor.execute(EditorActionIds.REDO)
        settle()
        assertFalse(document(machine).isDirty.value, "redo returns to the save point")
    }

    @Test
    fun `an external change reloads a clean file as one undoable step and is a conflict for a dirty one`() = scenario {
        val editor = openEditor(machine)
        val original = Files.readString(machine)

        Files.writeString(machine, "// changed on disk\n$original")
        watcher.report(machine)
        settle()
        assertEquals("// changed on disk\n$original", textOf(editor), "a clean document follows the disk")
        assertFalse(document(machine).isDirty.value)
        editor.execute(EditorActionIds.UNDO)
        settle()
        assertEquals(original, textOf(editor), "the reload is one undoable step")

        editor.execute(EditorActionIds.REDO)
        editor.caret.moveTo(0)
        editor.type("x")
        Files.writeString(machine, "// changed again\n$original")
        watcher.report(machine, FileChangeKind.MODIFIED)
        scheduler.advanceTimeBy(250.milliseconds)
        settle()
        val tab = state.tabs.tabs.value.single { it.path == machine }
        assertEquals(ExternalChangeKind.MODIFIED, tab.conflict)
        assertTrue(textOf(editor).startsWith("x"), "a dirty document keeps the buffer")

        state.resolveConflict(machine, ConflictResolution.RELOAD).joinSettled()
        assertEquals("// changed again\n$original", textOf(editor))
        assertEquals(null, state.tabs.tabs.value.single { it.path == machine }.conflict)
    }

    private fun textOf(editor: CodeEditorState): String = editor.session.text.snapshot.value.text

    private fun ProductionWorkbench.itemsOf(path: Path): List<ProblemItem> =
        state.problems.state.value.groups.filter { it.path == path }.flatMap { it.items }
}
