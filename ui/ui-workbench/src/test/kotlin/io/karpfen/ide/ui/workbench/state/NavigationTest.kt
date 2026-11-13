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
package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.state.ScrollRequest
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.problems.ProblemItem
import io.karpfen.ide.ui.workbench.testing.JdkLikeDesktopActions
import io.karpfen.ide.ui.workbench.testing.RecordingFileOperations
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests `navigateTo`. It covers opening the target, mapping versioned ranges into the live text with
 * `EditableText.mapRange`, and clamping. It also covers files the editor cannot open, and that the navigation requests
 * of editors are collected for their whole life.
 */
class NavigationTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()
    private val machine: Path get() = root.resolve("karpfen-runtime/single_robot_example/cleaning_robot.kstates")
    private val model: Path get() = root.resolve("karpfen-runtime/single_robot_example/cleaning_robot.kmodel")

    private fun scenario(
        prepare: () -> Unit = { Examples.copyTo(root) },
        fileOperations: RecordingFileOperations = RecordingFileOperations(),
        block: suspend TestWorkbench.() -> Unit,
    ) =
        runTest(timeout = 60.seconds) {
            prepare()
            withWorkbench(dir.resolve("settings"), fileOperations) {
                open(root)
                block()
            }
        }

    private fun TestWorkbench.text(path: Path): String = document(path).session.text.snapshot.value.text

    private fun TestWorkbench.insertAtStart(path: Path, text: String) {
        document(path).session.text.apply(listOf(TextEdit.insert(0, text)), EditOrigin.Typing)
        settle()
    }

    private fun TestWorkbench.problemsOf(path: Path): List<ProblemItem> =
        state.problems.state.value.local.firstOrNull { it.path == path }?.items.orEmpty()

    @Test
    fun `navigating into an unopened file opens it and centres the caret at the range start`() = scenario {
        val offset = Files.readString(machine).indexOf("\"observe\"") + 1
        val foreign = TextSnapshot(Files.readString(machine), 0)
        state.navigateTo(Location(machine, TextRange(offset, offset + 7), foreign)).joinSettled()

        val editor = assertNotNull(state.tabs.editorFor(machine))
        assertEquals(machine, state.tabs.active.value)
        assertEquals(offset, editor.caret.primary.position, "another lineage: the range is clamped, i.e. taken as is")
        assertEquals(ScrollRequest(offset, center = true), editor.scrollRequest.value)
        assertTrue(editor.focusRequested.value)
    }

    @Test
    fun `a range without a version or beyond the text is clamped`() = scenario {
        state.navigateTo(Location(machine, TextRange(1_000_000, 1_000_010))).joinSettled()
        val editor = assertNotNull(state.tabs.editorFor(machine))
        assertEquals(text(machine).length, editor.caret.primary.position)
    }

    @Test
    fun `problems navigation maps the range over edits above the target`() = scenario(prepare = {
        TestWorkbench.write(root.resolve("broken/broken.kmeta"), "type \"A\" \"a\" {\n    prop(\"x\", \"number\")\n    oops\n}\n")
    }) {
        val file = root.resolve("broken/broken.kmeta")
        val unopened = problemsOf(file).first()
        state.navigateTo(unopened.location).joinSettled()
        val editor = assertNotNull(state.tabs.editorFor(file))
        assertEquals(unopened.diagnostic.range.start, editor.caret.primary.position, "the disk text is the analyzed text")

        val opened = problemsOf(file).first()
        assertEquals(document(file).session.text.lineage, opened.lineage, "an open document's problems refer to its text")
        val header = "// header\n// more\n"
        insertAtStart(file, header)
        state.navigateTo(opened.location).joinSettled()
        assertEquals(opened.diagnostic.range.start + header.length, editor.caret.primary.position)
        assertTrue(text(file).substring(editor.caret.primary.position).startsWith("oops"))
    }

    @Test
    fun `outline navigation from the last good parse lands on the node after edits above it`() = scenario {
        val editor = openEditor(machine)
        insertAtStart(machine, "}}} broken\n")
        val outline = state.outline.state.value
        assertTrue(outline.isStale, "the outline comes from the last good parse")
        assertEquals(0L, outline.version)
        val observe = outline.nodes.flatMap { it.flatten() }.first { it.name == "observe" }

        state.navigateTo(assertNotNull(outline.locationOf(observe))).joinSettled()
        val caret = editor.caret.primary.position
        val atCaret = text(machine).substring(caret)
        assertTrue(atCaret.startsWith("observe\""), "caret at the state name, was ${atCaret.take(20)}")
    }

    @Test
    fun `a definition lookup finishing after a tab switch still navigates`() = scenario {
        val editor = openEditor(machine)
        val reference = text(machine).indexOf("TRANSITION \"ready\" -> \"observe\"") + "TRANSITION \"ready\" -> \"".length + 2
        editor.goToDefinition(reference)
        state.openFile(model)
        settle()

        assertEquals(machine, state.tabs.active.value, "the navigation request was collected while the tab was in the background")
        assertTrue(text(machine).substring(editor.caret.primary.position).startsWith("observe\" {"))
    }

    @Test
    fun `navigating to a descriptor problem opens the descriptor in a tab at the problem`() = scenario(prepare = {
        DescriptorExamples.copyExplicitContext(root.resolve("ctx"), DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE)
    }) {
        val descriptor = root.resolve("ctx").resolve(DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE)
        val problem = problemsOf(descriptor).first()

        state.navigateTo(problem.location).joinSettled()

        assertEquals(descriptor, state.tabs.active.value)
        val editor = assertNotNull(state.tabs.editorFor(descriptor))
        assertEquals(text(descriptor).indexOf("missing.kmodel"), editor.caret.primary.position)
        assertEquals(emptyList(), fileOperations.openedExternally)
        assertNoDialog()
    }

    @Test
    fun `a file of no karpfen language, such as a README, opens in the default application`() = scenario(prepare = {
        Examples.copyTo(root)
        Files.writeString(root.resolve("README.md"), "The lamp example.")
    }) {
        val readme = root.resolve("README.md")

        state.openFile(readme).joinSettled()

        assertEquals(listOf(readme), fileOperations.openedExternally)
        assertTrue(state.tabs.tabs.value.isEmpty())
        assertNoDialog()
    }

    @Test
    fun `navigating to a descriptor deleted since its problem was reported shows Cannot open, not an unexpected error`() =
        scenario(
            prepare = { DescriptorExamples.copyExplicitContext(root.resolve("ctx"), DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE) },
            fileOperations = RecordingFileOperations(platform = JdkLikeDesktopActions()),
        ) {
            val descriptor = root.resolve("ctx").resolve(DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE)
            val problem = problemsOf(descriptor).first()
            Files.delete(descriptor)

            state.navigateTo(problem.location)
            val error = answer(DialogRequest.Error::class, DialogResponse.Confirm)

            assertEquals("Cannot open ${descriptor.fileName}", error.title)
            assertEquals("Cannot open ${descriptor.fileName}: the file does not exist", error.message)
            assertNoDialog()
        }

    private fun OutlineNode.flatten(): List<OutlineNode> = listOf(this) + children.flatMap { it.flatten() }
}
