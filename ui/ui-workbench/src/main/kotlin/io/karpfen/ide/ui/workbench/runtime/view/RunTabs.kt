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
package io.karpfen.ide.ui.workbench.runtime.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.runtime.session.RuntimeSession
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.output.view.OutputView
import io.karpfen.ide.ui.workbench.runtime.RunEntry
import io.karpfen.ide.ui.workbench.runtime.RuntimeState
import io.karpfen.ide.ui.workbench.runtime.RuntimeTexts
import io.karpfen.ide.ui.workbench.runtime.navigationTarget
import java.nio.file.Path

/** The tabs of a run. */
private enum class RunTab(val title: String) {
    OVERVIEW("Overview"),
    MODEL("Model"),
    EVENTS("Events"),
    LOG("Log"),
}

/**
 * The tabs of the selected run [entry], which are Overview, Model, Events and Log. The Model and Events tabs need the
 * run's session. Before the run was launched, they say so.
 */
@Composable
internal fun RunTabs(runtime: RuntimeState, entry: RunEntry, root: Path, onNavigate: (Location) -> Unit, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf(RunTab.OVERVIEW) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RunTab.entries.forEach { each ->
                FilterChip(
                    selected = tab == each,
                    onClick = { tab = each },
                    label = { Text(each.title, style = KarpfenTheme.typography.uiSmall) },
                    modifier = Modifier.testTag(RuntimeViewTestTags.tab(each.title)),
                )
            }
        }
        val details = runtime.details(entry)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                RunTab.OVERVIEW -> OverviewTab(entry, root, onNavigate)
                RunTab.MODEL -> details?.let { ModelTab(runtime, entry, it) } ?: NotLaunched()
                RunTab.EVENTS -> details?.let { EventsTab(it) } ?: NotLaunched()
                RunTab.LOG -> LogTab(runtime)
            }
        }
    }
}

/**
 * The Overview tab. It shows the run's phase, the host problem if there is one, and the findings that block the run.
 * Once the run is launched, it also shows the environment key, the connection, the sync status, the active states of
 * each state machine, the deploy steps and the session's problems. A click on a finding with a file calls
 * [onNavigate]. All text of the tab can be selected with the mouse and copied.
 */
@Composable
private fun OverviewTab(entry: RunEntry, root: Path, onNavigate: (Location) -> Unit) {
    val colors = KarpfenTheme.colors
    SelectionContainer {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
            Line("Phase: ${RuntimeTexts.phase(entry.phase)}")
            entry.hostProblem?.let { Line("The runtime could not be started: ${RuntimeTexts.hostProblem(it)}", colors.error) }
            if (entry.findings.isNotEmpty()) {
                Heading("Findings that block the run")
                Problems(entry.findings, root, onNavigate)
            }
            entry.session?.let { SessionOverview(it, root, onNavigate) }
        }
    }
}

/** What the Overview shows of a launched [session]. */
@Composable
private fun SessionOverview(session: RuntimeSession, root: Path, onNavigate: (Location) -> Unit) {
    val progress by session.progress.collectAsState()
    val problems by session.problems.collectAsState()
    val model by session.model.collectAsState()
    val connection by session.connection.collectAsState()
    Line("Environment: ${progress.environment?.value ?: "not created yet"}")
    Line("Connection: ${RuntimeTexts.connection(connection)}")
    Line("Model: ${RuntimeTexts.mode(model)}, ${RuntimeTexts.sync(model)}")
    Heading("Active states")
    model.states.machines.values.forEach { machine ->
        Text(
            RuntimeTexts.activeStates(machine),
            color = KarpfenTheme.colors.text,
            style = KarpfenTheme.typography.monospace,
            modifier = Modifier.testTag(RuntimeViewTestTags.activeStates(machine.attachedTo)),
        )
    }
    Heading("Deployment")
    progress.steps.forEach { Line(RuntimeTexts.step(it)) }
    if (problems.isNotEmpty()) {
        Heading("Problems")
        Problems(problems, root, onNavigate)
    }
}

/**
 * Lists [problems], each with its file and position, coloured by severity. A click on a problem with a file opens the
 * file through [onNavigate].
 */
@Composable
private fun Problems(problems: List<DeployProblem>, root: Path, onNavigate: (Location) -> Unit) {
    val colors = KarpfenTheme.colors
    problems.forEachIndexed { index, problem ->
        val target = problem.navigationTarget()
        Text(
            RuntimeTexts.problem(problem, root),
            color = when (problem.severity) {
                Severity.ERROR -> colors.error
                Severity.WARNING -> colors.warning
                else -> colors.text
            },
            style = KarpfenTheme.typography.uiSmall,
            modifier = Modifier
                .fillMaxWidth()
                .let { if (target != null) it.clickable { onNavigate(target) } else it }
                .padding(vertical = 2.dp)
                .testTag(RuntimeViewTestTags.finding(index)),
        )
    }
}

/** The Log tab. It shows the runtime's log from the state's output buffer, with standard error in the error colour. */
@Composable
private fun LogTab(runtime: RuntimeState) {
    val log by runtime.log.state.collectAsState()
    OutputView(
        log,
        onFollowChange = runtime.log::setFollow,
        onClear = runtime.log::clear,
        readingPosition = runtime.log.readingPosition,
        onReadingPositionChange = runtime.log::keepReadingPosition,
        emptyText = "No output from the runtime",
        errorsInErrorColour = true,
    )
}

/** What a tab that needs the session shows before the run was launched. */
@Composable
private fun NotLaunched() {
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        Text("The run has not reached the runtime.", color = KarpfenTheme.colors.textMuted, style = KarpfenTheme.typography.uiSmall)
    }
}

/** A heading of a section of a tab. */
@Composable
internal fun Heading(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, color = KarpfenTheme.colors.text, style = KarpfenTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold))
}

/** One line of text, in [color] if given. */
@Composable
internal fun Line(text: String, color: Color = Color.Unspecified) {
    Text(text, color = if (color == Color.Unspecified) KarpfenTheme.colors.text else color, style = KarpfenTheme.typography.uiSmall)
}
