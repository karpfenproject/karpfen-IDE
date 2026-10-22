package io.karpfen.ide.core.workspace

import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.session.VersionedDiagnostics
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.analysis.AnalysisSources
import io.karpfen.ide.core.workspace.analysis.ProjectAnalyzer
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Covers open documents in a workspace: the one problems rule for open, unopened, loose and descriptor files, that
 * opening and closing changes no finding, the editor text and its debounce in the analysis, and project diagnostics in
 * sessions, including that an analysis started before a close and reopen never reaches the reopened document.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceDocumentsTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()
    private val ctx: Path get() = root.resolve("ctx")
    private val metamodel: Path get() = ctx.resolve("robot.kmeta")
    private val model: Path get() = ctx.resolve("robot.kmodel")
    private val mixed: Path get() = ctx.resolve("mixed.kmodel")
    private val loose: Path get() = ctx.resolve("zz_loose.kmeta")
    private val descriptor: Path get() = root.resolve("env/broken.kenv.json")

    private val validModel = "make object \"r\":\"Robot\" {\n    prop(\"speed\") -> \"1\"\n}\n"
    private val semanticError = "    prop(\"velocity\") -> \"2\"\n"

    /** A context with a DSL_TOOLS error, a file with FILE findings, a loose metamodel with a syntax error and a broken descriptor. */
    private fun writeProject() {
        Files.createDirectories(ctx)
        Files.createDirectories(descriptor.parent)
        Files.writeString(metamodel, "type \"Robot\" \"A robot\" {\n    prop(\"speed\", \"number\")\n}\n")
        Files.writeString(model, validModel.replace("}\n", "${semanticError}}\n"))
        Files.writeString(mixed, "make object \"m\":\"Robot\" {\r\n    prop(\"speed\") -> \"3\"\n}\r\n")
        Files.writeString(loose, "type \"Loose\" \"l\" {\n")
        Files.writeString(descriptor, "{\"metamodel\": \"a.kmeta\"")
    }

    @Test
    fun `problems follow the one rule for open, unopened, loose and descriptor files`() = runTest(timeout = 60.seconds) {
        writeProject()
        val workspace = TestWorkspaces(this).open(root)
        workspace.awaitAnalysis()
        val problems = workspace.problems.value

        assertEquals(setOf(metamodel, model, mixed, loose, descriptor), problems.files.map { it.path }.toSet())
        assertEquals(listOf(DiagnosticSource.DSL_TOOLS), problems.of(model)?.diagnostics?.map { it.source })
        assertEquals(listOf(WorkspaceDiagnosticCodes.MIXED_LINE_SEPARATORS), problems.of(mixed)?.diagnostics?.map { it.code })
        assertEquals(
            setOf(WorkspaceDiagnosticCodes.CONTEXT_MULTIPLE_METAMODELS, DiagnosticCodes.SYNTAX_ERROR),
            problems.of(loose)?.diagnostics?.map { it.code }?.toSet(),
        )
        assertEquals(listOf(DiagnosticCodes.SYNTAX_ERROR), problems.of(descriptor)?.diagnostics?.map { it.code })
        assertTrue(problems.files.none { it.isOpen })

        val document = workspace.openDocument(model)
        settle()

        val open = assertNotNull(workspace.problems.value.of(model))
        assertTrue(open.isOpen)
        val session: VersionedDiagnostics = document.session.diagnostics.value
        assertEquals(session.diagnostics, open.diagnostics)
        assertSame(session.snapshot, open.snapshot)
        assertEquals(problems.byFile(), workspace.problems.value.byFile(), "opening changes no finding")
    }

    @Test
    fun `opening and closing a context file, a file with loading findings, a loose file and a descriptor leaves problems unchanged`() = runTest(timeout = 60.seconds) {
        writeProject()
        val workspace = TestWorkspaces(this).open(root)
        workspace.awaitAnalysis()
        val before = workspace.problems.value.byFile()

        for (path in listOf(model, mixed, loose, metamodel, descriptor)) {
            val document = workspace.openDocument(path)
            settle()
            assertEquals(before, workspace.problems.value.byFile(), "after opening ${path.fileName}")
            assertEquals(before[path].orEmpty(), document.session.diagnostics.value.diagnostics, "the session of ${path.fileName}")

            workspace.documents.close(path)
            settle()
            assertEquals(before, workspace.problems.value.byFile(), "after closing ${path.fileName}")
        }
    }

    @Test
    fun `the analysis uses the editor text, debounced by 300 ms after the last edit`() = runTest(timeout = 60.seconds) {
        writeProject()
        val workspace = TestWorkspaces(this).open(root)
        val document = workspace.openDocument(model)
        settle()
        val generation = workspace.analysis.value.generation
        assertTrue(assertNotNull(workspace.problems.value.of(model)).diagnostics.any { it.source == DiagnosticSource.DSL_TOOLS })

        val text = document.session.text
        val start = text.snapshot.value.text.indexOf(semanticError)
        text.apply(listOf(TextEdit.delete(TextRange.ofLength(start, semanticError.length))), EditOrigin.Typing)
        runCurrent()
        assertTrue(workspace.isAnalyzing.value)
        advanceTimeBy(299.milliseconds)
        runCurrent()
        assertEquals(generation, workspace.analysis.value.generation, "still debouncing")

        advanceTimeBy(2.milliseconds)
        runCurrent()
        val analysis = workspace.analysis.value
        assertTrue(analysis.generation > generation)
        assertSame(text.snapshot.value, analysis.file(model)?.snapshot, "the session's snapshot, not the disk text")
        assertFalse(workspace.isAnalyzing.value)
        settle()
        assertEquals(null, workspace.problems.value.of(model), "the fixed editor text has no findings")
        assertEquals(validModel.replace("}\n", "${semanticError}}\n"), Files.readString(model), "nothing was saved")
    }

    @Test
    fun `project diagnostics reach the session of an open document and follow its edits`() = runTest(timeout = 60.seconds) {
        writeProject()
        val workspace = TestWorkspaces(this).open(root)
        val document = workspace.openDocument(model)
        settle()

        val text = document.session.text
        val found = document.session.diagnostics.value.diagnostics.single { it.source == DiagnosticSource.DSL_TOOLS }
        val name = text.snapshot.value.text.substring(found.range.start, found.range.end)
        val inserted = "// a comment line\n"
        text.apply(listOf(TextEdit.insert(0, inserted)), EditOrigin.Typing)
        runCurrent()

        val mapped = document.session.diagnostics.value.diagnostics.single { it.source == DiagnosticSource.DSL_TOOLS }
        assertEquals(found.range.start + inserted.length, mapped.range.start, "mapped into the edited text before re-analysis")
        assertEquals(name, text.snapshot.value.text.substring(mapped.range.start, mapped.range.end))
        assertTrue(name.isNotEmpty())
    }

    /**
     * The gated analyzer holds the analysis of the edited text until the document was closed and reopened, then lets it
     * publish.
     */
    @Test
    fun `an analysis that started before a close and reopen never reaches the reopened document`() = runTest(timeout = 60.seconds) {
        writeProject()
        Files.writeString(model, validModel)
        val gate = CompletableDeferred<Unit>()
        var holding = false
        val languages = defaultLanguageRegistry()
        val real = ProjectAnalyzer(languages, DslReader())
        val gated = ProjectAnalyzer { resolution: ContextResolution, sources: AnalysisSources, generation: Long ->
            real.analyze(resolution, sources, generation).also { if (holding) gate.await() }
        }
        val workspace = TestWorkspaces(this).open(root, analyzer = gated)
        val before = workspace.openDocument(model)
        settle()

        holding = true
        before.session.text.apply(listOf(TextEdit.insert(validModel.indexOf("}\n"), semanticError)), EditOrigin.Typing)
        settle(1.seconds)
        workspace.documents.close(model)
        val reopened = workspace.openDocument(model)
        val seen = ArrayList<VersionedDiagnostics>()
        backgroundScope.launch { reopened.session.diagnostics.collect { seen += it } }
        holding = false
        gate.complete(Unit)
        settle()

        assertTrue(seen.isNotEmpty())
        assertTrue(seen.none { versioned -> versioned.diagnostics.any { it.source == DiagnosticSource.DSL_TOOLS } }, "$seen")
        assertEquals(null, workspace.problems.value.of(model))
    }
}
