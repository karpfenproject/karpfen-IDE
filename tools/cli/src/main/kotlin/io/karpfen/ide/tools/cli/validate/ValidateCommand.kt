package io.karpfen.ide.tools.cli.validate

import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.core.workspace.WorkspaceOptions
import java.nio.file.Path

/**
 * `karpfen validate <dir>` (spec §9): opens a `Workspace` of [directory] through [workspaceFactory] (no watching), runs
 * `refresh()` and `awaitAnalysis()`, and reports `Workspace.problems`: the same single rule as the IDE's Problems view
 * (every karpfen file's own LEXER/PARSER/IDE diagnostics, loose files included, plus the project's DSL_TOOLS and
 * descriptor/context diagnostics), so the CLI and the IDE never disagree. Positions come from
 * `FileProblems.positionOf`, shown one-based. dsl-tools writes to stdout and stderr, and a workspace may start its
 * first analysis as soon as it is opened, so the whole workspace run — open, `refresh`, `awaitAnalysis`, collecting
 * `problems` and `close` (cancelling its scope) — happens inside [StandardOutputGuard.silenced]; the report and the
 * CLI's own messages are written afterwards through the guard's original streams.
 *
 * @param workspaceFactory creates the workspace; the default uses the built-in languages and options without watching.
 *   Tests pass `WorkspaceFactory(WorkspaceOptions(watch = false, …test dispatchers…))` or a fake.
 */
public class ValidateCommand(
    private val outputGuard: StandardOutputGuard = StandardOutputGuard(),
    private val workspaceFactory: WorkspaceFactory = WorkspaceFactory(WorkspaceOptions(watch = false)),
) {
    /** Validates [directory]. */
    public suspend fun run(directory: Path): ValidationReport = TODO("WP-APP: validate a directory")
}
