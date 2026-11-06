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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.runtime.host.HostStatus
import io.karpfen.ide.runtime.host.RuntimeHostKind
import io.karpfen.ide.ui.foundation.theme.KarpfenColors
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.runtime.DescriptorPick
import io.karpfen.ide.ui.workbench.runtime.RunEntry
import io.karpfen.ide.ui.workbench.runtime.RunPhase
import io.karpfen.ide.ui.workbench.runtime.RuntimeState
import io.karpfen.ide.ui.workbench.runtime.RuntimeTexts
import io.karpfen.ide.ui.workbench.settings.RuntimeSettings
import java.nio.file.Path

/**
 * The Runtime tool window over [runtime]: a header with the host's status, Start runtime and Stop runtime and the host
 * selector; below it the runs list with the selected run's tabs, or, without runs, how to run: the Run command with
 * its shortcut and the descriptor it would pick. Paths show relative to [root]; a clicked finding calls [onNavigate].
 * Renders the state and calls its commands only.
 */
@Composable
internal fun RuntimeToolWindow(runtime: RuntimeState, root: Path, onNavigate: (Location) -> Unit, modifier: Modifier = Modifier) {
    val settings by runtime.hostSettings.collectAsState()
    val status by runtime.host.collectAsState()
    val runs by runtime.runs.collectAsState()
    val selected by runtime.selected.collectAsState()
    Column(modifier.fillMaxSize()) {
        RuntimeHeader(
            settings = settings,
            status = status,
            onStart = { runtime.startRuntime() },
            onStop = { runtime.stopRuntime() },
            onSelectHost = { runtime.selectHost(it) },
            onApplyUrl = { runtime.setUrl(it) },
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(KarpfenTheme.colors.border))
        if (runs.isEmpty()) {
            val pick by runtime.nextPick.collectAsState()
            EmptyRuntime(runtime.runShortcut, pick, root)
        } else {
            Row(Modifier.fillMaxSize()) {
                RunsList(
                    runs = runs,
                    selected = selected,
                    onSelect = runtime::select,
                    onRerun = { runtime.rerun(it) },
                    onStop = { runtime.stop(it) },
                    onClearFinished = runtime::clearFinished,
                    modifier = Modifier.width(RUNS_WIDTH.dp).fillMaxHeight(),
                )
                Box(Modifier.width(1.dp).fillMaxHeight().background(KarpfenTheme.colors.border))
                selected?.let { RunTabs(runtime, it, root, onNavigate, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

/**
 * The header: the host's [status] in words, Start runtime and Stop runtime, and the host selector with the URL field,
 * whose text [onApplyUrl] writes when applied.
 */
@Composable
private fun RuntimeHeader(
    settings: RuntimeSettings,
    status: HostStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelectHost: (RuntimeHostKind) -> Unit,
    onApplyUrl: (String) -> Unit,
) {
    val colors = KarpfenTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            RuntimeTexts.hostStatus(settings, status),
            color = if (status is HostStatus.Failed) colors.error else colors.text,
            style = KarpfenTheme.typography.uiSmall,
            modifier = Modifier.weight(1f).testTag(RuntimeViewTestTags.HOST_STATUS),
        )
        val idle = status is HostStatus.Stopped || status is HostStatus.Failed
        SmallButton("Start runtime", RuntimeViewTestTags.START_RUNTIME, enabled = idle, onClick = onStart)
        SmallButton("Stop runtime", RuntimeViewTestTags.STOP_RUNTIME, enabled = !idle && status != HostStatus.Stopping, onClick = onStop)
        RuntimeHostKind.entries.forEach { kind ->
            FilterChip(
                selected = settings.host == kind,
                onClick = { onSelectHost(kind) },
                label = { Text(if (kind == RuntimeHostKind.DOCKER) "Docker" else "URL", style = KarpfenTheme.typography.uiSmall) },
                modifier = Modifier.testTag(RuntimeViewTestTags.host(kind)),
            )
        }
        if (settings.host == RuntimeHostKind.URL) {
            var url by remember(settings.url) { mutableStateOf(settings.url) }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                textStyle = KarpfenTheme.typography.uiSmall,
                modifier = Modifier.width(URL_WIDTH.dp).testTag(RuntimeViewTestTags.URL_FIELD),
            )
            SmallButton("Apply", RuntimeViewTestTags.URL_APPLY, enabled = url.trim() != settings.url) { onApplyUrl(url) }
        }
    }
}

/** How to run: the Run command with its [shortcut] and what it would run as [pick] says, relative to [root]. */
@Composable
private fun EmptyRuntime(shortcut: String, pick: DescriptorPick, root: Path) {
    val next = when (pick) {
        is DescriptorPick.Picked -> "It runs ${root.relativize(pick.descriptor)}."
        is DescriptorPick.Choice -> "It asks which of the ${pick.descriptors.size} environment descriptors to run."
        DescriptorPick.None ->
            "The workspace has no environment descriptor yet: create one with New Environment File in the Project tool window."
    }
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Nothing runs yet. Run ($shortcut, in the Run menu) deploys an environment descriptor of the workspace. $next",
            color = KarpfenTheme.colors.textMuted,
            style = KarpfenTheme.typography.ui,
            modifier = Modifier.align(Alignment.Center).testTag(RuntimeViewTestTags.EMPTY),
        )
    }
}

/**
 * The runs, newest first, each with its plan name, its environment key once known and its phase; the buttons act on
 * [selected].
 */
@Composable
private fun RunsList(
    runs: List<RunEntry>,
    selected: RunEntry?,
    onSelect: (RunEntry) -> Unit,
    onRerun: (RunEntry) -> Unit,
    onStop: (RunEntry) -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KarpfenTheme.colors
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallButton("Run again", RuntimeViewTestTags.RERUN, enabled = selected != null) { selected?.let(onRerun) }
            SmallButton("Stop", RuntimeViewTestTags.STOP, enabled = selected?.isStoppable == true) { selected?.let(onStop) }
            val anyFinished = runs.any { it.phase.isFinished }
            SmallButton("Clear finished", RuntimeViewTestTags.CLEAR_FINISHED, enabled = anyFinished, onClick = onClearFinished)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(runs, key = { it.id }) { run ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (run.id == selected?.id) colors.selection else Color.Transparent)
                        .clickable { onSelect(run) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag(RuntimeViewTestTags.run(run.id)),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(run.planName, color = colors.text, style = KarpfenTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold))
                        run.environment?.let { Text(it.value, color = colors.textMuted, style = KarpfenTheme.typography.uiSmall) }
                    }
                    PhaseLabel(run)
                }
            }
        }
    }
}

/** The phase of [run] as a small coloured label. */
@Composable
private fun PhaseLabel(run: RunEntry) {
    val color = phaseColor(run.phase, KarpfenTheme.colors)
    Text(
        RuntimeTexts.phase(run.phase),
        color = color,
        style = KarpfenTheme.typography.uiSmall,
        modifier = Modifier
            .background(color.copy(alpha = LABEL_ALPHA))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag(RuntimeViewTestTags.phase(run.id)),
    )
}

/** The colour of [phase]: errors for blocked and failed runs, the accent for live ones, muted otherwise. */
private fun phaseColor(phase: RunPhase, colors: KarpfenColors): Color = when (phase) {
    RunPhase.BLOCKED, RunPhase.FAILED -> colors.error
    RunPhase.RUNNING, RunPhase.READY -> colors.accent
    RunPhase.DEPLOYING, RunPhase.PREPARING, RunPhase.WAITING_FOR_RUNTIME -> colors.info
    RunPhase.STOPPED -> colors.textMuted
}

/** A small text button labelled [label] and tagged [tag]. */
@Composable
internal fun SmallButton(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Text(label, style = KarpfenTheme.typography.uiSmall)
    }
}

/** The width of the runs list in dp. */
private const val RUNS_WIDTH = 240

/** The width of the URL field in dp. */
private const val URL_WIDTH = 240

/** The opacity of a phase label's background. */
private const val LABEL_ALPHA = 0.15f
