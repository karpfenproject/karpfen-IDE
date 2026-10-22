package io.karpfen.ide.tools.cli.validate

import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import java.nio.file.Path

/**
 * `karpfen validate <dir>`: [run] opens a `Workspace` of the directory through [workspaceFactory] (no watching), runs
 * `refresh()` and `awaitAnalysis()`, and reports `Workspace.problems`: the same single rule as the IDE's Problems view
 * (every karpfen file's own LEXER/PARSER/IDE diagnostics, loose files included, plus the project's DSL_TOOLS and
 * descriptor/context diagnostics), so the CLI and the IDE never disagree. Positions come from
 * `FileProblems.positionOf`, shown one-based; a file or a directory the workspace cannot read is its ERROR `unreadable`
 * at 1:1, so a tree the CLI could not read never passes. dsl-tools writes to stdout and stderr, and a workspace may
 * start its first analysis as soon as it is opened, so the whole workspace run — open, `refresh`, `awaitAnalysis`,
 * collecting `problems`, and `close` (cancelling its scope) — happens inside [StandardOutputGuard.silenced]; the report
 * and the CLI's own messages are written afterwards through the guard's original streams.
 *
 * @param workspaceFactory creates the workspace; the default uses the built-in languages and options without watching.
 *   Tests pass `WorkspaceFactory(WorkspaceOptions(watch = false, …test dispatchers…))` or a fake.
 */
public class ValidateCommand(
    private val outputGuard: StandardOutputGuard = StandardOutputGuard(),
    private val workspaceFactory: WorkspaceFactory = WorkspaceFactory(WorkspaceOptions(watch = false)),
) {
    /**
     * Validates [directory]: every problem of the workspace, with paths relative to the directory (see
     * [ValidationReport]). The returned report is complete; printing it is the caller's job.
     *
     * @throws IllegalArgumentException if [directory] is not a directory (from the workspace factory).
     */
    public suspend fun run(directory: Path): ValidationReport {
        val root = directory.toAbsolutePath().normalize()
        val problems = outputGuard.silenced { collect(root) }
        return problems.toValidationReport(root)
    }

    /**
     * One workspace run in a child scope: the workspace is closed and every coroutine it started is cancelled before
     * this returns (also on failure), so no analysis can print after the guard restored the streams.
     */
    private suspend fun collect(root: Path): WorkspaceProblems = coroutineScope {
        val workspace = workspaceFactory.open(root, this)
        try {
            workspace.refresh()
            workspace.awaitAnalysis()
            workspace.problems.value
        } finally {
            workspace.close()
            coroutineContext.job.cancelChildren()
        }
    }
}
