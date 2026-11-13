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

import io.karpfen.ide.bridges.api.BridgeState
import io.karpfen.ide.runtime.session.RuntimeSession
import io.karpfen.ide.runtime.session.deploy.StepStatus
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.states.StackOrigin
import io.karpfen.ide.ui.workbench.bridges.BridgeTexts
import io.karpfen.ide.ui.workbench.bridges.RunBridges
import io.karpfen.ide.ui.workbench.runtime.RunEntry
import io.karpfen.ide.ui.workbench.runtime.RuntimeState
import io.karpfen.ide.ui.workbench.runtime.RuntimeTexts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Writes what a headless run does while it does it, one line per change. It follows the runtime state of the opened
 * project, as the Runtime tool window does, and writes:
 *
 * - the status of the runtime host whenever it changes, such as `Docker: building the runtime image, Step 7/14`,
 * - the phase of the run whenever it changes, such as `Run lamp: deploying`,
 * - why the runtime could not be started, where the run is captured and why its capture failed,
 * - the findings that block the deployment and the problems of the session, each once,
 * - every step of the deployment once it is done, skipped or failed,
 * - the active states of each state machine whenever the runtime reports a new stack, such as `lamp: on`,
 * - each bridge whenever its state changes, and each route of a bridge whenever it drops or fails another message.
 *
 * The headless mode starts one run, so the reporter follows the newest run of the list. The log of the runtime has a
 * reporter of its own, [RuntimeLogFollower].
 *
 * @param console where the lines go.
 * @param root the project folder, which the file names are relative to.
 */
internal class RunReporter(private val console: HeadlessConsole, private val root: Path) {

    /** Follows [runtime] in [scope] until the returned job is cancelled. */
    fun follow(scope: CoroutineScope, runtime: RuntimeState): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.host.map(RuntimeTexts::hostStatus)
                .distinctUntilChanged()
                .collect(console::line)
        }
        launch(start = CoroutineStart.UNDISPATCHED) { followRuns(this, runtime) }
    }

    /** Follows the newest run of [runtime], and its session and bridges once they exist, in [scope]. */
    private suspend fun followRuns(scope: CoroutineScope, runtime: RuntimeState) {
        var shown: RunEntry? = null
        var session: RuntimeSession? = null
        var bridges: RunBridges? = null
        runtime.runs.collect { runs ->
            val entry = runs.firstOrNull() ?: return@collect
            report(shown?.takeIf { it.id == entry.id }, entry)
            shown = entry
            entry.session?.takeIf { it !== session }?.let { launched ->
                session = launched
                scope.launch(start = CoroutineStart.UNDISPATCHED) { followSession(this, launched) }
            }
            if (bridges == null) {
                runtime.bridges(entry)?.let { started ->
                    bridges = started
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { followBridges(started) }
                }
            }
        }
    }

    /** Writes what changed from [previous] to [entry], or everything [entry] has to say if [previous] is null. */
    private fun report(previous: RunEntry?, entry: RunEntry) {
        if (entry.phase != previous?.phase) console.line(RunLines.phase(entry))
        entry.hostProblem?.takeIf { it != previous?.hostProblem }?.let {
            console.line("The runtime could not be started: ${RuntimeTexts.hostProblem(it)}")
        }
        entry.captureFile?.takeIf { it != previous?.captureFile }?.let { console.line(RuntimeTexts.captured(it, root)) }
        entry.captureProblem?.takeIf { it != previous?.captureProblem }?.let { console.line(RuntimeTexts.captureProblem(it)) }
        if (entry.findings != previous?.findings) {
            (entry.findings - previous?.findings.orEmpty().toSet()).forEach { console.line(RunLines.problem(it, root)) }
        }
    }

    /** Follows the deploy steps, the problems and the active states of [session] in [scope]. */
    private fun followSession(scope: CoroutineScope, session: RuntimeSession) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val written = HashMap<Int, StepStatus>()
            session.progress.collect { progress ->
                progress.steps.forEachIndexed { index, step ->
                    if (step.status in SETTLED && written[index] != step.status) {
                        written[index] = step.status
                        console.line(RuntimeTexts.step(step))
                    }
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val written = HashSet<DeployProblem>()
            session.problems.collect { problems ->
                problems.filter(written::add).forEach { console.line(RunLines.problem(it, root)) }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val written = HashMap<String, List<String>>()
            session.model.collect { model ->
                model.states.machines.values
                    .filter { it.origin == StackOrigin.REPORTED && written[it.attachedTo] != it.stack }
                    .forEach { machine ->
                        written[machine.attachedTo] = machine.stack
                        console.line(RuntimeTexts.activeStates(machine))
                    }
            }
        }
    }

    /** Follows the state of each bridge of [bridges] and the trouble on each of its routes. */
    private suspend fun followBridges(bridges: RunBridges) {
        val states = HashMap<Path, BridgeState>()
        val troubles = HashMap<String, Pair<Long, Long>>()
        bridges.runs.collect { runs ->
            runs.forEach { run ->
                if (states.put(run.file, run.status.state) != run.status.state) console.line(BridgeTexts.line(run))
                (run.status.inbound + run.status.outbound).forEach { route ->
                    val counts = route.dropped to route.failed
                    val key = "${run.file} ${route.label}"
                    if (troubles.put(key, counts) != counts && (route.dropped > 0 || route.failed > 0)) {
                        console.line("${run.name}: ${BridgeTexts.trouble(route)}")
                    }
                }
            }
        }
    }

    private companion object {
        /** The statuses of a deploy step that are written: those of a step that is through. */
        val SETTLED = setOf(StepStatus.DONE, StepStatus.SKIPPED, StepStatus.FAILED)
    }
}
