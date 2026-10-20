package io.karpfen.ide.tools.cli.validate

import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/**
 * The [ValidationReport] of these problems below [root] (absolute, normalized): one [ReportedProblem] per diagnostic,
 * nothing filtered or merged (the workspace's single problems rule decided what is reported), with the path relative
 * to [root] in `/` form ([reportPath]) and the one-based position of `FileProblems.positionOf`. Sorted by path, line
 * and column; ties keep the files' diagnostic order (`Diagnostic.ORDER`).
 */
internal fun WorkspaceProblems.toValidationReport(root: Path): ValidationReport {
    val problems = files.flatMap { file ->
        val path = reportPath(root, file.path)
        file.diagnostics.map { diagnostic ->
            val position = file.positionOf(diagnostic)
            ReportedProblem(
                path = path,
                line = position.line + 1,
                column = position.column + 1,
                severity = diagnostic.severity,
                message = diagnostic.message,
                code = diagnostic.code,
            )
        }
    }
    return ValidationReport(root, problems.sortedWith(REPORT_ORDER))
}

/** [path] relative to [root] with `/` separators; an absolute `/` path if it is not below [root]. */
internal fun reportPath(root: Path, path: Path): String {
    val normalized = path.toAbsolutePath().normalize()
    val shown = if (normalized.startsWith(root)) root.relativize(normalized) else normalized
    return shown.invariantSeparatorsPathString
}

/** Path, then line, then column (a stable sort keeps the diagnostic order for ties). */
private val REPORT_ORDER: Comparator<ReportedProblem> =
    compareBy<ReportedProblem> { it.path }.thenBy { it.line }.thenBy { it.column }
