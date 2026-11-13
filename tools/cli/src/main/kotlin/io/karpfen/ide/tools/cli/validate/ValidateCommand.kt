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

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.core.workspace.validation.ProjectValidation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import java.nio.file.Path

/**
 * The command `karpfen validate <dir>`. [run] opens a `Workspace` of the directory through [workspaceFactory], without
 * watching, switches on every optional language feature of KStates, runs `refresh()` and then `validate()`, and reports
 * the findings of that validation of the whole project. That is the same validation the IDE's Validate button runs:
 * `Workspace.problems`, which the IDE's Problems view shows, together with the checks of dsl-tools across the files of
 * each modeling context, whose codes start with `project.`. The command does not read the project settings of the IDE,
 * though, so it allows every construct that dsl-tools allows by default and reports no `kstates.feature-off`, where the
 * IDE reports the constructs of the features the project leaves off. It covers the LEXER, PARSER and IDE diagnostics of
 * every karpfen file, loose files included, plus the project's DSL_TOOLS, descriptor and context diagnostics and the
 * findings of the checks across files. Positions are shown one-based. A file or a directory the workspace cannot read
 * gets the ERROR `unreadable` at 1:1, so a tree the CLI could not read never passes.
 *
 * Nothing of the workspace run prints to standard output. dsl-tools logs through SLF4J, and the tool's logging
 * configuration writes to standard error only. So the caller prints the report on standard output afterwards.
 *
 * @param workspaceFactory creates the workspace. The default uses the built-in languages and options without
 *   watching. Tests pass `WorkspaceFactory(WorkspaceOptions(watch = false, …))` with test dispatchers, or a fake.
 */
public class ValidateCommand(
    private val workspaceFactory: WorkspaceFactory = WorkspaceFactory(WorkspaceOptions(watch = false)),
) {
    /**
     * Validates [directory] and returns every problem of the workspace as a [ValidationReport], with paths relative
     * to the directory. The returned report is complete. Printing it is the caller's job.
     *
     * @throws IllegalArgumentException if [directory] is not a directory. The workspace factory throws it.
     */
    public suspend fun run(directory: Path): ValidationReport {
        val root = directory.toAbsolutePath().normalize()
        return collect(root).toValidationReport(root)
    }

    /**
     * One workspace run in a child scope. The workspace is closed and every coroutine it started is cancelled before
     * this returns, also on failure, so no analysis keeps running after the report.
     */
    private suspend fun collect(root: Path): ProjectValidation = coroutineScope {
        val workspace = workspaceFactory.open(root, this)
        try {
            workspace.setLanguageFeatures(KstatesFeature.ALL)
            workspace.refresh()
            workspace.validate()
        } finally {
            workspace.close()
            coroutineContext.job.cancelChildren()
        }
    }
}
