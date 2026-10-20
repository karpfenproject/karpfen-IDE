package io.karpfen.ide.tools.cli.validate

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.problems.FileProblems
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ReportMappingTest {

    private val root: Path = Path.of("/work/project").toAbsolutePath().normalize()

    private fun diagnostic(start: Int, severity: Severity = Severity.ERROR, code: String = "c$start") =
        Diagnostic(TextRange(start, start), severity, "message $start", code, DiagnosticSource.PARSER)

    private fun file(relative: String, text: String?, vararg diagnostics: Diagnostic) = FileProblems(
        path = root.resolve(relative),
        kind = FileKind.of(relative.substringAfterLast('/')),
        diagnostics = diagnostics.sortedWith(Diagnostic.ORDER),
        snapshot = text?.let { TextSnapshot(it, version = 3) },
        isOpen = false,
    )

    @Test
    fun `every diagnostic becomes a problem with a one-based position and a slash path relative to the root`() {
        val text = "first\nsecond line\n\tthird"
        val problems = WorkspaceProblems(
            generation = 7,
            files = listOf(file("a/b/robot.kstates", text, diagnostic(0), diagnostic(8, Severity.WARNING), diagnostic(text.length))),
        )

        val report = problems.toValidationReport(root)

        assertEquals(root, report.root)
        assertEquals(
            listOf(
                ReportedProblem("a/b/robot.kstates", 1, 1, Severity.ERROR, "message 0", "c0"),
                ReportedProblem("a/b/robot.kstates", 2, 3, Severity.WARNING, "message 8", "c8"),
                ReportedProblem("a/b/robot.kstates", 3, 7, Severity.ERROR, "message ${text.length}", "c${text.length}"),
            ),
            report.problems,
        )
    }

    @Test
    fun `problems are sorted by path, line and column and keep the diagnostic order for ties`() {
        val problems = WorkspaceProblems(
            generation = 1,
            files = listOf(
                file("z.kmeta", "x\ny", diagnostic(2)),
                file("a.kmodel", "x\ny", diagnostic(2, Severity.INFO, "info"), diagnostic(2, Severity.ERROR, "error"), diagnostic(0)),
            ),
        )

        val report = problems.toValidationReport(root)

        assertEquals(
            listOf("a.kmodel:1:1:c0", "a.kmodel:2:1:error", "a.kmodel:2:1:info", "z.kmeta:2:1:c2"),
            report.problems.map { "${it.path}:${it.line}:${it.column}:${it.code}" },
        )
    }

    @Test
    fun `without a snapshot the position is the start of the file`() {
        val report = WorkspaceProblems(1, listOf(file("unreadable.kmeta", text = null, diagnostic(40)))).toValidationReport(root)

        assertEquals(1 to 1, report.problems.single().let { it.line to it.column })
    }

    @Test
    fun `no problems give an empty report without errors`() {
        val report = WorkspaceProblems.EMPTY.toValidationReport(root)

        assertEquals(ValidationReport(root, emptyList()), report)
        assertEquals(false, report.hasErrors)
    }

    @Test
    fun `report paths are relative with slashes below the root and absolute outside it`() {
        assertEquals("ctx/robot.kenv.json", reportPath(root, root.resolve("ctx").resolve("robot.kenv.json")))
        assertEquals("x.kmeta", reportPath(root, root.resolve("ctx/../x.kmeta")))
        val outside = root.resolveSibling("other").resolve("y.kmeta")
        assertEquals(outside.toString().replace('\\', '/'), reportPath(root, outside))
    }
}
