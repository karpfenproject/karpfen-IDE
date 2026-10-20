package io.karpfen.ide.core.workspace.problems

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.document.session.VersionedDiagnostics
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.analysis.AnalyzedFile
import io.karpfen.ide.core.workspace.analysis.ContextAnalysis
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.context.ModelingContext
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.core.workspace.files.FileKind
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The one problems rule (decision 17, [WorkspaceProblems]) on hand-built analyses. */
class WorkspaceProblemsAggregatorTest {

    private val root = Path.of("/ws").toAbsolutePath()
    private val aggregator = WorkspaceProblemsAggregator()

    private fun diagnostic(start: Int, source: DiagnosticSource, code: String, severity: Severity = Severity.ERROR) =
        Diagnostic(TextRange(start, start + 1), severity, code, code, source)

    private val lexer = diagnostic(3, DiagnosticSource.LEXER, DiagnosticCodes.LEXER_ERROR)
    private val parser = diagnostic(5, DiagnosticSource.PARSER, DiagnosticCodes.SYNTAX_ERROR)
    private val dslTools = diagnostic(1, DiagnosticSource.DSL_TOOLS, DiagnosticCodes.DSL_TOOLS_ERROR)
    private val fileFinding = diagnostic(2, DiagnosticSource.IDE, WorkspaceDiagnosticCodes.MIXED_LINE_SEPARATORS, Severity.INFO)

    private fun analyzed(name: String, snapshot: TextSnapshot = TextSnapshot("x".repeat(20), 0), vararg syntax: Diagnostic) =
        AnalyzedFile(
            root.resolve(name), FileKind.of(name), snapshot,
            ParseOutcome(KarpfenLanguage.KMETA, snapshot.version, syntax.toList(), emptyList(), SyntaxIndex.EMPTY, emptyList(), emptyList(), null, emptyList()),
            lexicalDiagnostics = listOf(lexer),
            fileDiagnostics = listOf(fileFinding),
        )

    @Test
    fun `an unopened context file merges its own diagnostics with its project diagnostics`() {
        val file = analyzed("a.kmeta", syntax = arrayOf(parser, lexer.copy(message = "parser's copy")))
        val context = ContextAnalysis(ModelingContext(root, null, file.path, null, emptyList(), emptyList()), mapOf(file.path to file), null, null, emptyMap(), emptyMap())
        val analysis = ProjectAnalysis(4, listOf(context), mapOf(file.path to listOf(dslTools)))

        val problems = aggregator.aggregate(analysis, emptyList(), emptyMap())

        val only = problems.files.single()
        assertEquals(listOf(dslTools, fileFinding, lexer, parser), only.diagnostics, "LEXER copies of the parse are dropped")
        assertSame(file.snapshot, only.snapshot)
        assertFalse(only.isOpen)
        assertEquals(4, problems.generation)
    }

    @Test
    fun `a loose file is treated like an unopened context file`() {
        val file = analyzed("loose.kstates")
        val info = diagnostic(0, DiagnosticSource.IDE, WorkspaceDiagnosticCodes.CONTEXT_MULTIPLE_METAMODELS, Severity.INFO)
        val analysis = ProjectAnalysis(1, emptyList(), mapOf(file.path to listOf(info)), mapOf(file.path to file))

        val problems = aggregator.aggregate(analysis, emptyList(), emptyMap())

        assertEquals(listOf(info, fileFinding, lexer), problems.of(file.path)?.diagnostics)
        assertEquals(FileKind.KSTATES, problems.of(file.path)?.kind)
    }

    @Test
    fun `an open document contributes its session's diagnostics instead of the analysis`() {
        val file = analyzed("a.kmeta")
        val live = TextSnapshot("live text", 9)
        val sessionDiagnostics = listOf(parser)
        val analysis = ProjectAnalysis(2, emptyList(), mapOf(file.path to listOf(dslTools)), mapOf(file.path to file))

        val problems = aggregator.aggregate(
            analysis,
            listOf(OpenDocumentDiagnostics(file.path, FileKind.KMETA, VersionedDiagnostics(live, sessionDiagnostics))),
            emptyMap(),
        )

        val open = problems.files.single()
        assertEquals(sessionDiagnostics, open.diagnostics)
        assertSame(live, open.snapshot)
        assertTrue(open.isOpen)
    }

    @Test
    fun `a descriptor gets its project diagnostics on its loaded text, files without diagnostics are absent`() {
        val descriptor = root.resolve("robot.kenv.json")
        val text = TextSnapshot("{\"metamodel\": \"x\"}", 0)
        val error = diagnostic(1, DiagnosticSource.IDE, WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID)
        val clean = AnalyzedFile(
            root.resolve("clean.kmeta"), FileKind.KMETA, TextSnapshot("", 0),
            ParseOutcome(KarpfenLanguage.KMETA, 0, emptyList(), emptyList(), SyntaxIndex.EMPTY, emptyList(), emptyList(), null, emptyList()),
        )
        val analysis = ProjectAnalysis(3, emptyList(), mapOf(descriptor to listOf(error)), mapOf(clean.path to clean))

        val problems = aggregator.aggregate(
            analysis,
            listOf(OpenDocumentDiagnostics(root.resolve("open.kmeta"), FileKind.KMETA, VersionedDiagnostics(TextSnapshot("", 0), emptyList()))),
            mapOf(descriptor to text),
        )

        val only = problems.files.single()
        assertEquals(descriptor, only.path)
        assertEquals(FileKind.ENV_DESCRIPTOR, only.kind)
        assertSame(text, only.snapshot)
        assertEquals(1, problems.errorCount)
    }

    @Test
    fun `files are sorted by path`() {
        val b = analyzed("b.kmeta")
        val a = analyzed("sub/a.kmeta")
        val c = analyzed("a.kmeta")
        val analysis = ProjectAnalysis(1, emptyList(), emptyMap(), listOf(b, a, c).associateBy { it.path })

        val problems = aggregator.aggregate(analysis, emptyList(), emptyMap())

        assertEquals(listOf(c.path, b.path, a.path), problems.files.map { it.path })
        assertEquals(3, problems.errorCount)
    }
}
