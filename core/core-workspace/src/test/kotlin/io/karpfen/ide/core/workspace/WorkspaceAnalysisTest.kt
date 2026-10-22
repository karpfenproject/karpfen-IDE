package io.karpfen.ide.core.workspace

import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.text.TextPosition
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Covers end-to-end workspace scenarios on real folders: contexts, analysis and problems. Every example context
 * analyzes without findings; descriptors, unreadable files and directories, several metamodels in one directory,
 * broken metamodels and refreshes produce the findings the workspace documents.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceAnalysisTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()

    @Test
    fun `the examples folder scans, resolves and analyzes with zero findings`(reporter: TestReporter) = runTest(timeout = 120.seconds) {
        Examples.copyTo(root)
        val workspace = TestWorkspaces(this).open(root)

        val mark = TimeSource.Monotonic.markNow()
        val analysis = workspace.awaitAnalysis()
        val elapsed = mark.elapsedNow()

        assertEquals(Examples.files().size, workspace.files.value.files().size)
        assertEquals(Examples.contexts().map { root.resolve(it.directory) }, workspace.contexts.value.contexts.map { it.directory })
        assertEquals(emptyList(), workspace.contexts.value.looseFiles)
        assertEquals(analysis, workspace.analysis.value)
        assertTrue(analysis.diagnostics.values.flatten().none { it.source == DiagnosticSource.DSL_TOOLS }, "zero DSL_TOOLS diagnostics")
        assertEquals(WorkspaceProblems(analysis.generation, emptyList()), workspace.problems.value)
        assertFalse(workspace.isAnalyzing.value)
        reporter.publishEntry("workspace start + analysis of the examples", "$elapsed (first run, incl. scan and resolution)")
    }

    @Test
    fun `an explicit context from a descriptor analyzes cleanly`() = runTest(timeout = 60.seconds) {
        val descriptor = DescriptorExamples.copyExplicitContext(root.resolve("ctx")).toAbsolutePath().normalize()
        val workspace = TestWorkspaces(this).open(root)

        val analysis = workspace.awaitAnalysis()

        val context = analysis.contexts.single()
        assertEquals(descriptor, context.context.descriptor)
        assertEquals(DescriptorExamples.ATTACHED_TO, context.context.stateMachines.single().attachedTo)
        assertNotNull(context.events)
        assertEquals(emptyList(), workspace.problems.value.files)
    }

    @Test
    fun `descriptor findings appear in problems with positions in the descriptor text, unnamed files are loose`() = runTest(timeout = 60.seconds) {
        val descriptor = DescriptorExamples.copyExplicitContext(root.resolve("ctx"), DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE)
            .toAbsolutePath().normalize()
        val text = Files.readString(descriptor)
        val workspace = TestWorkspaces(this).open(root)

        val analysis = workspace.awaitAnalysis()

        val problems = assertNotNull(workspace.problems.value.of(descriptor))
        assertEquals(FileKind.ENV_DESCRIPTOR, problems.kind)
        assertEquals(
            listOf(WorkspaceDiagnosticCodes.DESCRIPTOR_MISSING_FILE, WorkspaceDiagnosticCodes.DESCRIPTOR_UNKNOWN_KEY),
            problems.diagnostics.map { it.code },
        )
        val missing = problems.diagnostics.first()
        val line = text.lines().indexOfFirst { "missing.kmodel" in it }
        assertEquals(TextPosition(line, text.lines()[line].indexOf("missing.kmodel")), problems.positionOf(missing))
        assertEquals(listOf(root.resolve("ctx/cleaning_robot.kmodel")), analysis.looseFiles.keys.toList())
        assertEquals(1, workspace.problems.value.errorCount)
        assertEquals(1, workspace.problems.value.warningCount)
    }

    @Test
    fun `a descriptor naming a state machine as metamodel is an error, never a silent pass`() = runTest(timeout = 60.seconds) {
        val ctx = root.resolve("ctx")
        val descriptor = DescriptorExamples.copyExplicitContext(ctx).toAbsolutePath().normalize()
        Files.writeString(descriptor, Files.readString(descriptor).replace("\"metamodel\": \"cleaning_robot.kmeta\"", "\"metamodel\": \"cleaning_robot.kstates\""))
        val model = ctx.resolve("cleaning_robot.kmodel")
        Files.writeString(model, Files.readString(model).replace("prop(\"d_closest_obstacle\")", "prop(\"no_such_property\")"))
        val workspace = TestWorkspaces(this).open(root)

        val analysis = workspace.awaitAnalysis()

        assertEquals(null, analysis.contexts.single().metamodel, "nothing converts")
        val problems = assertNotNull(workspace.problems.value.of(descriptor))
        val finding = problems.diagnostics.single()
        assertEquals(WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID to Severity.ERROR, finding.code to finding.severity)
        assertTrue("cleaning_robot.kstates" in finding.message && "metamodel" in finding.message, finding.message)
        assertEquals(1, workspace.problems.value.errorCount)
    }

    @Test
    fun `files and directories the workspace cannot read are unreadable errors in problems`() = runTest(timeout = 60.seconds) {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"), "needs POSIX permissions")
        val broken = Files.writeString(root.resolve("broken.kstates"), "STATEMACHINE")
        val sub = Files.createDirectories(root.resolve("sub"))
        Files.writeString(sub.resolve("m.kstates"), "STATEMACHINE")
        listOf(broken, sub).forEach { Files.setPosixFilePermissions(it, emptySet()) }
        try {
            assumeFalse(Files.isReadable(broken), "the user can read any file (root)")
            val workspace = TestWorkspaces(this).open(root)

            workspace.awaitAnalysis()

            val problems = workspace.problems.value
            val file = assertNotNull(problems.of(broken))
            assertEquals(
                setOf(WorkspaceDiagnosticCodes.UNREADABLE to Severity.ERROR, WorkspaceDiagnosticCodes.CONTEXT_NO_METAMODEL to Severity.INFO),
                file.diagnostics.map { it.code to it.severity }.toSet(),
            )
            assertEquals(TextPosition.ORIGIN, file.positionOf(file.diagnostics.first { it.code == WorkspaceDiagnosticCodes.UNREADABLE }))
            val directory = assertNotNull(problems.of(sub))
            assertEquals(listOf(WorkspaceDiagnosticCodes.UNREADABLE), directory.diagnostics.map { it.code })
            assertEquals(2, problems.errorCount)
        } finally {
            Files.setPosixFilePermissions(sub, PosixFilePermissions.fromString("rwx------"))
            Files.setPosixFilePermissions(broken, PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun `a root the workspace cannot list is an unreadable error, not an empty project`() = runTest(timeout = 60.seconds) {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"), "needs POSIX permissions")
        val folder = Files.createDirectories(root.resolve("locked"))
        Files.writeString(folder.resolve("robot.kmeta"), "type \"A\" \"a\" {\n")
        Files.setPosixFilePermissions(folder, emptySet())
        try {
            assumeFalse(Files.isReadable(folder), "the user can read any directory (root)")
            val workspace = TestWorkspaces(this).open(folder)

            workspace.awaitAnalysis()

            assertFalse(workspace.files.value.root.isReadable)
            val problems = workspace.problems.value
            assertEquals(listOf(WorkspaceDiagnosticCodes.UNREADABLE), assertNotNull(problems.of(folder)).diagnostics.map { it.code })
            assertEquals(1, problems.errorCount)
        } finally {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `of two metamodels the broken one is loose and its PARSER error is in problems next to the INFO`() = runTest(timeout = 60.seconds) {
        val ctx = root.resolve("ctx")
        Files.createDirectories(ctx)
        Files.writeString(ctx.resolve("a.kmeta"), "type \"A\" \"a\" {\n    prop(\"x\", \"number\")\n}\n")
        Files.writeString(ctx.resolve("b.kmeta"), "type \"B\" \"b\" {\n    prop(\"y\", \"number\")\n")
        val workspace = TestWorkspaces(this).open(root)

        val analysis = workspace.awaitAnalysis()

        assertEquals(listOf(ctx.resolve("b.kmeta")), workspace.contexts.value.looseFiles)
        assertEquals(ctx.resolve("a.kmeta"), analysis.contexts.single().context.metamodel)
        val b = assertNotNull(workspace.problems.value.of(ctx.resolve("b.kmeta")))
        assertEquals(
            setOf(DiagnosticSource.PARSER to Severity.ERROR, DiagnosticSource.IDE to Severity.INFO),
            b.diagnostics.map { it.source to it.severity }.toSet(),
        )
        assertTrue(b.diagnostics.any { it.code == WorkspaceDiagnosticCodes.CONTEXT_MULTIPLE_METAMODELS })
        val a = assertNotNull(workspace.problems.value.of(ctx.resolve("a.kmeta")))
        assertEquals(listOf(WorkspaceDiagnosticCodes.CONTEXT_MULTIPLE_METAMODELS), a.diagnostics.map { it.code })
        assertTrue(analysis.diagnostics.values.flatten().none { it.source == DiagnosticSource.DSL_TOOLS })
    }

    @Test
    fun `models with and without syntax errors next to a broken metamodel report no DSL_TOOLS diagnostic`() = runTest(timeout = 60.seconds) {
        val ctx = root.resolve("robot")
        Files.createDirectories(ctx)
        Examples.files().filter { it.directory == DescriptorExamples.CONTEXT_DIRECTORY }.forEach { file ->
            val text = if (file.fileName == "cleaning_robot.kmeta") file.text.replace("type \"Room\"", "type \"Room\" \"broken\" \"x\"") else file.text
            Files.writeString(ctx.resolve(file.fileName), text)
        }
        Files.writeString(ctx.resolve("second.kmodel"), "make object \"x\":\"Vector\" {\n    prop(\"x\") -> \n}\n")
        val workspace = TestWorkspaces(this).open(root)

        val analysis = workspace.awaitAnalysis()

        val problems = workspace.problems.value
        assertTrue(problems.files.flatMap { it.diagnostics }.none { it.source == DiagnosticSource.DSL_TOOLS }, "${problems.byFile()}")
        for (name in listOf("cleaning_robot.kmeta", "second.kmodel")) {
            val own = assertNotNull(problems.of(ctx.resolve(name)), name).diagnostics
            assertTrue(own.all { it.source == DiagnosticSource.PARSER }, "$name: $own")
        }
        assertEquals(emptyMap(), analysis.contexts.single().models, "no model converts without the metamodel")
    }

    @Test
    fun `refresh picks up new and changed files and the analysis follows the disk`() = runTest(timeout = 60.seconds) {
        val ctx = root.resolve("ctx")
        Files.createDirectories(ctx)
        Files.writeString(ctx.resolve("a.kmeta"), "type \"A\" \"a\" {\n}\n")
        val workspace = TestWorkspaces(this).open(root)
        workspace.awaitAnalysis()
        assertEquals(emptyList(), workspace.problems.value.files)

        Files.writeString(ctx.resolve("a.kmeta"), "type \"A\" \"a\" {\n")
        Files.writeString(ctx.resolve("b.kmodel"), "make object \"b\":\"A\" { }\n")
        workspace.refresh()

        assertNotNull(workspace.files.value.find(ctx.resolve("b.kmodel")))
        assertEquals(listOf(ctx.resolve("b.kmodel")), workspace.contexts.value.contexts.single().models)
        runCurrent()
        assertTrue(workspace.isAnalyzing.value, "the rescan scheduled an analysis")
        settle()
        assertFalse(workspace.isAnalyzing.value)
        val metamodel = assertNotNull(workspace.problems.value.of(ctx.resolve("a.kmeta")))
        assertTrue(metamodel.diagnostics.any { it.source == DiagnosticSource.PARSER })
    }

    @Test
    fun `the factory rejects a root that is not a directory and closing is idempotent`() = runTest {
        val file = Files.writeString(root.resolve("file.kmeta"), "")
        assertFailsWith<IllegalArgumentException> { TestWorkspaces(this).open(file) }

        val workspace = WorkspaceFactory(TestWorkspaces(this).options(), FakeFileWatcher()).open(root, backgroundScope)
        workspace.close()
        workspace.close()
        assertFailsWith<IllegalStateException> { workspace.awaitAnalysis() }
    }
}
