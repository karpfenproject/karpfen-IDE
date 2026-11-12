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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.runtime.session.RuntimeSession
import io.karpfen.ide.runtime.session.deploy.StepStatus
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.ui.foundation.layout.SplitOrientation
import io.karpfen.ide.ui.foundation.layout.SplitPane
import io.karpfen.ide.ui.foundation.layout.SplitSide
import io.karpfen.ide.ui.foundation.theme.KarpfenColors
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.output.OutputLine
import io.karpfen.ide.ui.workbench.output.view.OutputView
import io.karpfen.ide.ui.workbench.runtime.RunEntry
import io.karpfen.ide.ui.workbench.runtime.RuntimeState
import io.karpfen.ide.ui.workbench.runtime.RuntimeTexts
import io.karpfen.ide.ui.workbench.runtime.StatechartLog
import io.karpfen.ide.ui.workbench.runtime.navigationTarget
import java.nio.file.Path

/**
 * The three panes of the selected run [entry], next to each other: Model, Events and Log. They are shown together, so
 * the effect of an event on the model and on the state machines is in sight while the event is sent. Each pane starts
 * with a third of the width, and the dividers between them can be dragged.
 *
 * The Model and Events panes need the run's session. Before the run was launched, they say so. The Log pane also
 * holds what the run itself has to tell: why it did not start, the active states and the deployment.
 */
@Composable
internal fun RunPanes(runtime: RuntimeState, entry: RunEntry, root: Path, onNavigate: (Location) -> Unit, modifier: Modifier = Modifier) {
    val details = runtime.details(entry)
    BoxWithConstraints(modifier) {
        val third = maxWidth / PANES
        var modelWidth by remember { mutableStateOf(third) }
        var logWidth by remember { mutableStateOf(third) }
        SplitPane(
            orientation = SplitOrientation.HORIZONTAL,
            fixedSide = SplitSide.FIRST,
            fixedSize = modelWidth,
            onFixedSizeChange = { modelWidth = it },
            modifier = Modifier.fillMaxSize(),
            minFirst = MIN_PANE_WIDTH.dp,
            minSecond = (2 * MIN_PANE_WIDTH).dp,
            first = { details?.let { ModelPane(runtime, entry, it) } ?: NotLaunched("Model") },
            second = {
                SplitPane(
                    orientation = SplitOrientation.HORIZONTAL,
                    fixedSide = SplitSide.SECOND,
                    fixedSize = logWidth,
                    onFixedSizeChange = { logWidth = it },
                    modifier = Modifier.fillMaxSize(),
                    minFirst = MIN_PANE_WIDTH.dp,
                    minSecond = MIN_PANE_WIDTH.dp,
                    first = { details?.let { EventsPane(it) } ?: NotLaunched("Events") },
                    second = { LogPane(runtime, entry, root, onNavigate) },
                )
            },
        )
    }
}

/**
 * The Log pane. Its toolbar holds the Simplified toggle, the Follow toggle and the Clear button. Below the toolbar
 * comes what the run has to tell, as [RunSummary] shows it, and then the runtime's log.
 *
 * While Simplified is on, the log shows only the steps of the state machines and the errors, in the wording of
 * [StatechartLog]. While it is off, the log shows every line of the runtime's output, with standard error in the
 * error colour. The toggle starts switched on, because following the state machines is what the pane is mostly used
 * for.
 */
@Composable
private fun LogPane(runtime: RuntimeState, entry: RunEntry, root: Path, onNavigate: (Location) -> Unit) {
    val log by runtime.log.state.collectAsState()
    var simplified by remember { mutableStateOf(true) }
    val shown = remember(log, simplified) { if (simplified) log.copy(lines = log.lines.filter(StatechartLog::shows)) else log }
    val subjectWidth = remember(shown, simplified) { if (simplified) StatechartLog.subjectWidth(shown.lines) else 0 }
    val colors = KarpfenTheme.colors
    val statechartText: (OutputLine) -> AnnotatedString = { line -> statechartLine(line, subjectWidth, colors) }
    OutputView(
        shown,
        onFollowChange = runtime.log::setFollow,
        onClear = runtime.log::clear,
        readingPosition = runtime.log.readingPosition,
        onReadingPositionChange = runtime.log::keepReadingPosition,
        emptyText = if (simplified) NO_STEPS else "No output from the runtime",
        errorsInErrorColour = true,
        toolbarStart = {
            PaneTitle("Log")
            FilterChip(
                selected = simplified,
                onClick = { simplified = !simplified },
                label = { Text("Simplified", style = KarpfenTheme.typography.uiSmall) },
                modifier = Modifier.testTag(RuntimeViewTestTags.SIMPLIFIED),
            )
        },
        belowToolbar = { RunSummary(entry, root, onNavigate) },
        lineText = if (simplified) statechartText else null,
    )
}

/**
 * One [line] of the simplified log. The time is muted, the state machine's name is in the accent colour and padded to
 * [subjectWidth] characters, and the text follows. An error is in the error colour as a whole.
 */
private fun statechartLine(line: OutputLine, subjectWidth: Int, colors: KarpfenColors): AnnotatedString = buildAnnotatedString {
    val entry = StatechartLog.entry(line)
    val error = entry.isError
    withStyle(SpanStyle(color = if (error) colors.error else colors.textMuted)) { append("${entry.time}  ") }
    if (subjectWidth > 0) {
        withStyle(SpanStyle(color = if (error) colors.error else colors.accent, fontWeight = FontWeight.SemiBold)) {
            append(entry.subject.padEnd(subjectWidth))
        }
        append("  ")
    }
    withStyle(SpanStyle(color = if (error) colors.error else colors.text)) { append(entry.text) }
}

/**
 * What the run [entry] has to tell, above its log. It shows why the runtime could not be started and the findings that
 * block the run. Once the run is launched, it shows the active states of each state machine and one line about the
 * deployment. A click on that line opens the details: the connection, every deploy step and the session's problems.
 * The details start open while the deployment is under way or has a problem, and closed once it went through. A click
 * on a finding with a file calls [onNavigate]. All of the text can be selected with the mouse and copied.
 */
@Composable
private fun RunSummary(entry: RunEntry, root: Path, onNavigate: (Location) -> Unit) {
    val colors = KarpfenTheme.colors
    SelectionContainer {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = SUMMARY_MAX_HEIGHT.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            entry.hostProblem?.let { Line("The runtime could not be started: ${RuntimeTexts.hostProblem(it)}", colors.error) }
            if (entry.findings.isNotEmpty()) {
                Heading("Findings that block the run")
                Problems(entry.findings, root, onNavigate)
            }
            entry.session?.let { SessionSummary(it, root, onNavigate) }
        }
    }
}

/** What [RunSummary] shows of a launched [session]. */
@Composable
private fun SessionSummary(session: RuntimeSession, root: Path, onNavigate: (Location) -> Unit) {
    val progress by session.progress.collectAsState()
    val problems by session.problems.collectAsState()
    val model by session.model.collectAsState()
    val connection by session.connection.collectAsState()
    val colors = KarpfenTheme.colors
    model.states.machines.values.forEach { machine ->
        Text(
            RuntimeTexts.activeStates(machine),
            color = colors.text,
            style = KarpfenTheme.typography.monospace.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.testTag(RuntimeViewTestTags.activeStates(machine.attachedTo)),
        )
    }
    val settled = progress.steps.isNotEmpty() && progress.steps.all { it.status == StepStatus.DONE || it.status == StepStatus.SKIPPED }
    var chosen by remember(session) { mutableStateOf<Boolean?>(null) }
    val open = chosen ?: (!settled || problems.isNotEmpty())
    Text(
        "${if (open) "▾" else "▸"} ${RuntimeTexts.deployment(progress.steps)}",
        color = if (progress.steps.any { it.status == StepStatus.FAILED }) colors.error else colors.textMuted,
        style = KarpfenTheme.typography.uiSmall,
        modifier = Modifier.fillMaxWidth().clickable { chosen = !open }.testTag(RuntimeViewTestTags.DEPLOYMENT),
    )
    if (open) {
        Line("Environment: ${progress.environment?.value ?: "not created yet"}")
        Line("Connection: ${RuntimeTexts.connection(connection)}")
        progress.steps.forEach { Line(RuntimeTexts.step(it)) }
        if (problems.isNotEmpty()) {
            Heading("Problems")
            Problems(problems, root, onNavigate)
        }
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

/** What the pane named [title] shows before the run was launched, since it needs the run's session. */
@Composable
private fun NotLaunched(title: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        PaneTitle(title)
        Box(Modifier.fillMaxSize().padding(8.dp)) {
            Text("The run has not reached the runtime.", color = KarpfenTheme.colors.textMuted, style = KarpfenTheme.typography.uiSmall)
        }
    }
}

/** The title of a pane of the run. */
@Composable
internal fun PaneTitle(text: String) {
    Text(text, color = KarpfenTheme.colors.text, style = KarpfenTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold))
}

/** A heading of a section of a pane. */
@Composable
internal fun Heading(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, color = KarpfenTheme.colors.text, style = KarpfenTheme.typography.uiSmall.copy(fontWeight = FontWeight.SemiBold))
}

/** One line of text, in [color] if given. */
@Composable
internal fun Line(text: String, color: Color = Color.Unspecified) {
    Text(text, color = if (color == Color.Unspecified) KarpfenTheme.colors.text else color, style = KarpfenTheme.typography.uiSmall)
}

/** What the simplified log says while it has no line to show. */
private const val NO_STEPS = "No step of a state machine yet. Switch Simplified off to see the whole output of the runtime."

/** The number of panes of a run. */
private const val PANES = 3

/** The smallest width of a pane in dp. */
private const val MIN_PANE_WIDTH = 160

/** The greatest height of the run's summary above the log in dp. A longer summary scrolls. */
private const val SUMMARY_MAX_HEIGHT = 160
