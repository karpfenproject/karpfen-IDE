package io.karpfen.ide.core.workspace

import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.core.workspace.document.OpenDocument
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
 * An environment descriptor open in an editor counts with its unsaved text: its findings appear in its session at the
 * edited ranges and in the problems, and the contexts and the analysis follow its edits; syntax errors are reported
 * once; closing it without saving falls back to the disk.
 */
class WorkspaceDescriptorEditingTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()
    private val ctx: Path get() = root.resolve("ctx")

    /** The diagnostics of the open [document] with the text they cover. */
    private fun OpenDocument.findings(): List<Pair<String, String>> {
        val text = session.text.snapshot.value.text
        return session.diagnostics.value.diagnostics.map { it.code to text.substring(it.range.start, it.range.end) }
    }

    /** Replaces the first [old] of the document's text by [new], as the user would type it. */
    private fun OpenDocument.replace(old: String, new: String) {
        val start = session.text.snapshot.value.text.indexOf(old)
        require(start >= 0) { "'$old' not in the text" }
        session.text.apply(listOf(TextEdit.replace(TextRange.ofLength(start, old.length), new)), EditOrigin.Typing)
    }

    @Test
    fun `unsaved edits of an open descriptor change its findings, the contexts and the analysis`() = runTest(timeout = 60.seconds) {
        val descriptor = DescriptorExamples.copyExplicitContext(ctx).toAbsolutePath().normalize()
        val workspace = TestWorkspaces(this).open(root)
        workspace.awaitAnalysis()
        val document = workspace.openDocument(descriptor)
        settle()
        assertEquals(emptyList(), document.findings())
        val model = ctx.resolve("cleaning_robot.kmodel")
        assertTrue(model in workspace.contexts.value.contexts.single(), "the model is in the descriptor's context")

        document.replace("cleaning_robot.kmodel", "missing.kmodel")
        document.replace("\"tickDelayMs\"", "\"tickDelay\"")
        settle()

        assertEquals(
            listOf(WorkspaceDiagnosticCodes.DESCRIPTOR_MISSING_FILE to "missing.kmodel", WorkspaceDiagnosticCodes.DESCRIPTOR_UNKNOWN_KEY to "tickDelay"),
            document.findings(),
            "found in the unsaved text, at its ranges",
        )
        val problems = assertNotNull(workspace.problems.value.of(descriptor))
        assertTrue(problems.isOpen)
        assertEquals(document.session.diagnostics.value.diagnostics, problems.diagnostics)
        assertFalse(model in workspace.contexts.value.contexts.single(), "the context follows the edit")
        assertTrue(model in workspace.analysis.value.looseFiles, "and so does the analysis: the model is loose now")
        assertTrue(Files.readString(descriptor).contains("cleaning_robot.kmodel"), "nothing was saved")
    }

    @Test
    fun `a syntax error of an open descriptor is reported once and closing without saving falls back to the disk`() = runTest(timeout = 60.seconds) {
        val descriptor = DescriptorExamples.copyExplicitContext(ctx).toAbsolutePath().normalize()
        val workspace = TestWorkspaces(this).open(root)
        workspace.awaitAnalysis()
        val document = workspace.openDocument(descriptor)
        settle()

        document.replace("\"model\":", "\"model\"")
        settle()

        val syntax = document.session.diagnostics.value.diagnostics
        assertEquals(listOf(DiagnosticSource.PARSER), syntax.map { it.source }, "$syntax")
        assertEquals(DiagnosticCodes.SYNTAX_ERROR, syntax.single().code)
        assertEquals(syntax, workspace.problems.value.of(descriptor)?.diagnostics)
        assertEquals(emptyList(), workspace.contexts.value.contexts.filter { it.descriptor == descriptor }, "a broken descriptor defines no context")

        workspace.documents.close(descriptor)
        settle()

        assertEquals(null, workspace.problems.value.of(descriptor), "the disk text has no findings")
        assertEquals(descriptor, workspace.contexts.value.contexts.single { it.directory == ctx }.descriptor)
    }
}
