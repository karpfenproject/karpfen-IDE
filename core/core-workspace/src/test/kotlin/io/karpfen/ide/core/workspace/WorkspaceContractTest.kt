package io.karpfen.ide.core.workspace

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.analysis.AnalyzedFile
import io.karpfen.ide.core.workspace.analysis.ContextAnalysis
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.analysis.SourceText
import io.karpfen.ide.core.workspace.problems.FileProblems
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.context.ModelingContext
import io.karpfen.ide.core.workspace.context.StateMachineBinding
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the complete values of core-workspace on their own: file kinds, tree order and lookup, context membership,
 * option validation, the per-file diagnostic merge of analyzed files, the file order of analyses, and that workspace
 * and document diagnostic codes are distinct. No workspace implementation code runs.
 */
class WorkspaceContractTest {

    private val root: Path = Path.of("/ws").toAbsolutePath()

    @Test
    fun `file kinds come from names`() {
        assertEquals(FileKind.EVENTS_KMETA, FileKind.of("events.KMETA"))
        assertEquals(FileKind.KMETA, FileKind.of("robot.kmeta"))
        assertEquals(FileKind.KSTATES, FileKind.of("robot.kstates"))
        assertEquals(FileKind.ENV_DESCRIPTOR, FileKind.of("robot.kenv.json"))
        assertEquals(FileKind.LAYOUT, FileKind.of("robot.kstates.layout.json"))
        assertEquals(FileKind.OTHER, FileKind.of("README.md"))
        assertTrue(FileKind.EVENTS_KMETA.isKarpfenSource)
    }

    @Test
    fun `trees list directories first in case-insensitive order and find nodes`() {
        val file = WorkspaceNode.File(root.resolve("b/robot.kmeta"), "robot.kmeta", FileKind.KMETA)
        val directory = WorkspaceNode.Directory(root.resolve("b"), "b", listOf(file))
        val readme = WorkspaceNode.File(root.resolve("A.md"), "A.md", FileKind.OTHER)
        val tree = WorkspaceTree(WorkspaceNode.Directory(root, "ws", listOf(directory, readme)))

        assertEquals(listOf(file, readme), tree.files())
        assertEquals(file, tree.find(root.resolve("b/robot.kmeta")))
        assertNull(tree.find(root.resolve("c")))
        assertFailsWith<IllegalArgumentException> { WorkspaceNode.Directory(root, "ws", listOf(readme, directory)) }
    }

    @Test
    fun `contexts and analyses answer membership queries`() {
        val machine = root.resolve("robot.kstates")
        val context = ModelingContext(
            directory = root,
            descriptor = null,
            metamodel = root.resolve("robot.kmeta"),
            events = null,
            models = listOf(root.resolve("robot.kmodel")),
            stateMachines = listOf(StateMachineBinding(machine, attachedTo = null)),
        )

        assertTrue(context.isImplicit)
        assertTrue(machine in context)
        assertEquals(3, context.files.size)
        assertEquals(listOf(context), ContextResolution(listOf(context), emptyMap()).contextsOf(machine))
        assertNull(ProjectAnalysis.EMPTY.contextOf(machine))
        assertEquals(emptyList(), ProjectAnalysis.EMPTY.diagnosticsFor(machine))
    }

    @Test
    fun `workspace options validate durations`() {
        assertTrue(".git" in WorkspaceOptions.DEFAULT_IGNORED_DIRECTORIES)
        assertFailsWith<IllegalArgumentException> {
            WorkspaceOptions(analysisDebounce = kotlin.time.Duration.parse("-1s"))
        }
    }

    @Test
    fun `an analyzed file merges its own diagnostics like a session`() {
        fun diagnostic(start: Int, severity: Severity, code: String, source: DiagnosticSource) =
            Diagnostic(TextRange(start, start + 1), severity, code, code, source)
        val lexer = diagnostic(1, Severity.ERROR, DiagnosticCodes.LEXER_ERROR, DiagnosticSource.LEXER)
        val parser = diagnostic(4, Severity.ERROR, DiagnosticCodes.SYNTAX_ERROR, DiagnosticSource.PARSER)
        val standalone = diagnostic(0, Severity.ERROR, "kmeta.unknown-target-type", DiagnosticSource.DSL_TOOLS)
        val file = diagnostic(2, Severity.INFO, WorkspaceDiagnosticCodes.MIXED_LINE_SEPARATORS, DiagnosticSource.IDE)
        val snapshot = TextSnapshot("type \"A\" \"d\" { }", 0)
        val parse = ParseOutcome(
            KarpfenLanguage.KMETA, 0, listOf(lexer, parser), emptyList(), SyntaxIndex.EMPTY, emptyList(), emptyList(),
            standaloneModel = null, standaloneDiagnostics = listOf(standalone),
        )
        val analyzed = AnalyzedFile(root.resolve("a.kmeta"), FileKind.KMETA, snapshot, parse, listOf(lexer), listOf(file))

        assertEquals(listOf(standalone, lexer, file, parser), analyzed.ownDiagnostics, "the parser's LEXER copy is dropped")
        assertEquals(analyzed, ProjectAnalysis(1, emptyList(), emptyMap(), mapOf(analyzed.path to analyzed)).file(analyzed.path))
        assertFailsWith<IllegalArgumentException> { AnalyzedFile(analyzed.path, FileKind.KMETA, TextSnapshot("", 1), parse) }
        assertFailsWith<IllegalArgumentException> { SourceText(snapshot, parse = null, tokens = TokenizeResult.empty(3)) }

        val problems = WorkspaceProblems(1, listOf(FileProblems(analyzed.path, FileKind.KMETA, analyzed.ownDiagnostics, snapshot, isOpen = false)))
        assertEquals(3, problems.errorCount)
        assertEquals(0, problems.warningCount)
        assertNull(problems.of(root.resolve("b.kmeta")))
        assertFailsWith<IllegalArgumentException> { FileProblems(analyzed.path, FileKind.KMETA, emptyList(), null, isOpen = false) }
    }

    @Test
    fun `analyses list context files in context order before loose files`() {
        val parse = ParseOutcome(
            KarpfenLanguage.KMETA, 0, emptyList(), emptyList(), SyntaxIndex.EMPTY, emptyList(), emptyList(),
            standaloneModel = null, standaloneDiagnostics = emptyList(),
        )
        fun analyzed(name: String) = AnalyzedFile(root.resolve(name), FileKind.of(name), TextSnapshot("", 0), parse)
        fun context(vararg files: AnalyzedFile) = ContextAnalysis(
            ModelingContext(root, null, files.first().path, null, emptyList(), emptyList()),
            files.associateBy { it.path }, metamodel = null, events = null, models = emptyMap(), stateMachines = emptyMap(),
        )
        val first = context(analyzed("b.kmeta"), analyzed("shared.kmeta"))
        val second = context(analyzed("a.kmeta"), analyzed("shared.kmeta"))
        val loose = analyzed("loose.kmeta")
        val analysis = ProjectAnalysis(1, listOf(first, second), emptyMap(), mapOf(loose.path to loose))

        assertEquals(listOf("b.kmeta", "shared.kmeta", "a.kmeta", "loose.kmeta"), analysis.files.keys.map { it.fileName.toString() })
        assertTrue(analysis.files[root.resolve("shared.kmeta")] === first.files[root.resolve("shared.kmeta")], "the first context wins")
        assertTrue(analysis.file(root.resolve("shared.kmeta")) === analysis.files[root.resolve("shared.kmeta")])
    }

    @Test
    fun `workspace and document diagnostic codes are distinct`() {
        fun codes(holder: Any) = holder::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
        val workspace = codes(WorkspaceDiagnosticCodes)
        val document = codes(DiagnosticCodes)

        assertEquals(8, workspace.size, "reflection found $workspace")
        assertTrue(document.isNotEmpty())
        assertEquals((workspace + document).size, (workspace + document).toSet().size, "a code is defined twice")
    }
}
