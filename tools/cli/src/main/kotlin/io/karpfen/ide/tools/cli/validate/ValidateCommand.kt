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
 * at 1:1, so a tree the CLI could not read never passes.
 *
 * Nothing of the workspace run prints to standard output: dsl-tools logs through SLF4J, and the tool's logging
 * configuration writes to standard error only, so the caller prints the report on standard output afterwards.
 *
 * @param workspaceFactory creates the workspace; the default uses the built-in languages and options without watching.
 *   Tests pass `WorkspaceFactory(WorkspaceOptions(watch = false, …test dispatchers…))` or a fake.
 */
public class ValidateCommand(
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
        return collect(root).toValidationReport(root)
    }

    /**
     * One workspace run in a child scope: the workspace is closed and every coroutine it started is cancelled before
     * this returns (also on failure), so no analysis keeps running after the report.
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
