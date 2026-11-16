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

import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.ui.workbench.output.OutputLine
import io.karpfen.ide.ui.workbench.runtime.RunEntry
import io.karpfen.ide.ui.workbench.runtime.RuntimeTexts
import io.karpfen.ide.ui.workbench.runtime.StatechartLog
import java.nio.file.Path

/**
 * The wording of a headless run, one line per thing that happens. Wherever the window has words for something, these
 * lines use them through `RuntimeTexts`, `StatechartLog` and `BridgeTexts`, so the headless mode and the Runtime tool
 * window say the same. The object is pure and holds no state.
 */
internal object RunLines {

    /**
     * The line that opens a run of [descriptor] with [bridges], both named relative to [root], captured if [capture]
     * and with parallel branches on threads of their own if [trueParallel].
     */
    fun start(root: Path, descriptor: Path, bridges: List<Path>, capture: Boolean, trueParallel: Boolean = false): String {
        val with = if (bridges.isEmpty()) "" else " with " + bridges.joinToString(", ") { nameOf(root, it) }
        return "Running ${nameOf(root, descriptor)}$with" + (if (capture) ", captured to a file" else "") +
            if (trueParallel) ", parallel branches on threads of their own" else ""
    }

    /** The phase of the run [entry], such as `Run lamp: waiting for the runtime`. */
    fun phase(entry: RunEntry): String = "Run ${entry.planName}: ${RuntimeTexts.phase(entry.phase)}"

    /**
     * A finding of the preparation or a problem of the session, such as
     * `warning: lamp.kmodel:3:5: The object lamp differs from the model the IDE read.`.
     */
    fun problem(problem: DeployProblem, root: Path): String =
        "${problem.severity.name.lowercase()}: ${RuntimeTexts.problem(problem, root)}"

    /** One line of the runtime's log in the short form of the Log pane's Simplified view, such as `12:41:30.123  lamp  starts in off`. */
    fun statechart(line: OutputLine): String {
        val entry = StatechartLog.entry(line)
        return listOf(entry.time, entry.subject, entry.text).filter { it.isNotEmpty() }.joinToString(SEPARATOR)
    }

    /** One line of the runtime's log as it is, after the stream it came from, such as `[stdout] Started environment`. */
    fun raw(line: OutputLine): String = "[${line.logger}] ${line.message}"

    /** [file] relative to [root] with `/` between its parts, or its whole path if it is not below [root]. */
    fun nameOf(root: Path, file: Path): String =
        if (file.startsWith(root) && file != root) root.relativize(file).joinToString("/") else file.toString()

    /** What separates the time, the state machine and the text of a line of the simplified log. */
    private const val SEPARATOR = "  "

    /** The line that a stop was asked for, by Ctrl+C or a terminate signal. */
    const val STOP_REQUESTED: String = "Stop requested."

    /** The line before the run with its bridges and then the runtime are shut down. */
    const val SHUTTING_DOWN: String = "Shutting down the run and its bridges, then the runtime."

    /** The line once everything is shut down. */
    const val SHUT_DOWN: String = "Shut down."

    /** The line after the warnings of a project without errors, whose run goes on. */
    const val GOES_ON_DESPITE_WARNINGS: String = "The run goes on despite the warnings."

    /** The line of a run that the errors of the project keep from starting. */
    const val BLOCKED_BY_ERRORS: String = "The run does not start: the project has errors."

    /** The line of a run that did not start for another reason, which a message before it names. */
    const val NOT_STARTED: String = "The run did not start."

    /** The line of a run whose deployment the checks before it blocked. */
    const val DEPLOYMENT_BLOCKED: String = "The run does not start: the deployment is blocked."

    /** The line of a run that failed: the runtime could not be reached, the deployment failed or the engine died. */
    const val FAILED: String = "The run failed."

    /** The line of a run that stopped on its own. */
    const val ENDED: String = "The run ended on its own."
}
