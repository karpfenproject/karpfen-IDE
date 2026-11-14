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
package io.karpfen.ide.app.headless

import io.karpfen.ide.app.IdeSession
import io.karpfen.ide.core.workspace.validation.ProjectValidation
import io.karpfen.ide.ui.workbench.runtime.RunChoice
import io.karpfen.ide.ui.workbench.runtime.RunPhase
import io.karpfen.ide.ui.workbench.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.yield
import java.nio.file.Path

/**
 * `karpfen-ide run <folder> …`. It does what Run in the window does, without a dialog, and writes what happens.
 *
 * It opens the folder as the window does, with the settings the project file gives the run. It takes the descriptor
 * and the bridge files from the command line as [RunSelection] describes, and a wrong one is a usage error. Then it
 * starts the run through `RuntimeState.run` with a [RunChoice] of those bridges, the capture and the true parallel
 * branches of the command line, and no debugger, so the run never halts at a breakpoint. The run saves nothing, because no file is open, and validates
 * the whole project first. A project with errors does not run: the report is written as `validate` writes it, and the
 * status is [HeadlessExitCodes.ERRORS_FOUND]. A project with warnings and no errors runs: its report is written as
 * `validate` writes it, followed by [RunLines.GOES_ON_DESPITE_WARNINGS], and then the run goes on.
 *
 * While the run goes on, [RunReporter] and [RuntimeLogFollower] write what it does. The run goes on until [stop] is
 * requested, or until it ends on its own: its deployment is blocked or fails, the runtime cannot be reached, or the
 * engine dies. Either way the runtime of the workspace is shut down through `WorkbenchState.shutdownRuntime`, which
 * stops the run with its bridges and its environment first and then closes the host, which removes a container the
 * IDE started. A run that ended on its own says why and ends with [HeadlessExitCodes.FAILURE]. A stopped run ends with
 * [HeadlessExitCodes.OK], although the process of a run stopped by a signal ends with the status the virtual machine
 * gives it.
 *
 * @param console where everything is written.
 * @param prepare opens a folder, which is `IdeApplication.prepare`.
 * @param stop the stop that a signal asks for.
 */
internal class HeadlessRun(
    private val console: HeadlessConsole,
    private val prepare: suspend (CoroutineScope, Path) -> IdeSession,
    private val stop: HeadlessStop,
) {
    /** How a run ended, with the line that says so and the status it ends with. */
    private enum class RunEnd(val line: String?, val status: Int) {
        STOPPED(RunLines.STOP_REQUESTED, HeadlessExitCodes.OK),
        BLOCKED_BY_ERRORS(RunLines.BLOCKED_BY_ERRORS, HeadlessExitCodes.ERRORS_FOUND),
        NOT_STARTED(RunLines.NOT_STARTED, HeadlessExitCodes.FAILURE),
        DEPLOYMENT_BLOCKED(RunLines.DEPLOYMENT_BLOCKED, HeadlessExitCodes.FAILURE),
        FAILED(RunLines.FAILED, HeadlessExitCodes.FAILURE),
        ENDED(RunLines.ENDED, HeadlessExitCodes.FAILURE),
    }

    /** Runs the project in the folder of [command], driving the workbench in [scope], and returns the status. */
    suspend fun run(scope: CoroutineScope, command: HeadlessCommand.Run): Int {
        val project = HeadlessProject.open(scope, command.folder, console, prepare) ?: return HeadlessExitCodes.FAILURE
        project.use {
            val selection = RunSelection.of(
                command,
                project.workspace.root,
                project.workspace.files.value,
                project.state.settings.value.languageFeatures,
            )
            if (selection is RunSelection.Wrong) {
                console.message(selection.message)
                console.error(HeadlessUsage.TEXT)
                return HeadlessExitCodes.USAGE
            }
            val chosen = selection as RunSelection.Chosen
            val runtime = project.state.runtime.value
            if (runtime == null) {
                console.message("the project has no runtime")
                return HeadlessExitCodes.FAILURE
            }
            return runUntilEnd(scope, project, runtime, chosen, command)
        }
    }

    /** Runs [chosen] on [runtime] of [project] until it ends or is stopped, shuts the runtime down and returns the status. */
    private suspend fun runUntilEnd(
        scope: CoroutineScope,
        project: HeadlessProject,
        runtime: RuntimeState,
        chosen: RunSelection.Chosen,
        command: HeadlessCommand.Run,
    ): Int {
        val root = project.workspace.root
        console.line(RunLines.start(root, chosen.descriptor, chosen.bridges, command.capture, command.trueParallel))
        val reporting = RunReporter(console, root).follow(scope, runtime)
        val logging = RuntimeLogFollower(console, command.verbose).follow(scope, runtime.logs)
        val warnings = project.state.validation.observeRunChecks(::writeWarnings)
        try {
            val started = runtime.run(chosen.descriptor, RunChoice(chosen.bridges, command.capture, debugger = false, trueParallel = command.trueParallel))
            val ended = scope.async { endOf(runtime, started, project) }
            val end = select {
                stop.requested.onAwait { RunEnd.STOPPED }
                ended.onAwait { it }
            }
            ended.cancel()
            end.line?.let(console::line)
            console.line(RunLines.SHUTTING_DOWN)
            project.state.shutdownRuntime()
            yield()
            console.line(RunLines.SHUT_DOWN)
            return end.status
        } finally {
            warnings.dispose()
            reporting.cancel()
            logging.cancel()
        }
    }

    /**
     * Writes [report], the check before the run, as `validate` writes it, followed by
     * [RunLines.GOES_ON_DESPITE_WARNINGS], if it has warnings and no errors. A report with errors is written by the
     * dialog that blocks the run, and a report without warnings is not written.
     */
    private fun writeWarnings(report: ProjectValidation) {
        if (report.hasErrors || report.warningCount == 0) return
        ValidationLines.of(report).forEach(console::line)
        console.line(RunLines.GOES_ON_DESPITE_WARNINGS)
    }

    /**
     * Waits for the end of the run that [started] starts on [runtime]. A run that never got an entry was kept from
     * starting, by the errors of the project if a report of [project] says so. A run with an entry ends when its phase
     * is finished.
     */
    private suspend fun endOf(runtime: RuntimeState, started: Job, project: HeadlessProject): RunEnd {
        started.join()
        val id = runtime.runs.value.firstOrNull()?.id
            ?: return if (project.dialogs.lastReport?.hasErrors == true) RunEnd.BLOCKED_BY_ERRORS else RunEnd.NOT_STARTED
        val finished = runtime.runs.map { runs -> runs.firstOrNull { it.id == id } }.first { it == null || it.phase.isFinished }
        return when (finished?.phase) {
            RunPhase.BLOCKED -> RunEnd.DEPLOYMENT_BLOCKED
            RunPhase.FAILED -> RunEnd.FAILED
            else -> RunEnd.ENDED
        }
    }
}
