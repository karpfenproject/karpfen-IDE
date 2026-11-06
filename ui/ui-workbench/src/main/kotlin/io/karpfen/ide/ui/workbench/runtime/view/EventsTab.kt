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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.session.events.DomainOrigin
import io.karpfen.ide.runtime.session.events.PayloadKind
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.runtime.DomainEventEntry
import io.karpfen.ide.ui.workbench.runtime.InjectorState
import io.karpfen.ide.ui.workbench.runtime.RunDetails
import io.karpfen.ide.ui.workbench.runtime.RuntimeTexts

/**
 * The Events tab of a launched run: the domain event log, newest first, each event with its time, domain, name, source
 * and its payload on one line; the event clicked last shows its whole payload below the log. Below it the injector.
 */
@Composable
internal fun EventsTab(details: RunDetails) {
    val events by details.events.collectAsState()
    var chosen by remember(details) { mutableStateOf<Long?>(null) }
    val colors = KarpfenTheme.colors
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(events, key = { it.sequence }) { entry ->
                Text(
                    eventLine(entry),
                    color = colors.text,
                    style = KarpfenTheme.typography.monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (entry.sequence == chosen) colors.selection else Color.Transparent)
                        .clickable { chosen = entry.sequence }
                        .padding(horizontal = 8.dp, vertical = 1.dp)
                        .testTag(RuntimeViewTestTags.event(entry.sequence)),
                )
            }
        }
        events.firstOrNull { it.sequence == chosen }?.let { entry ->
            Text(
                RuntimeTexts.payload(entry).ifEmpty { "(no payload)" },
                color = colors.textMuted,
                style = KarpfenTheme.typography.monospace,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag(RuntimeViewTestTags.EVENT_DETAIL),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        EventInjector(details.injector)
    }
}

/** One line of the event log for [entry]. */
private fun eventLine(entry: DomainEventEntry): String =
    listOf(
        RuntimeTexts.time(entry),
        entry.event.domain,
        RuntimeTexts.eventName(entry),
        RuntimeTexts.source(entry),
        RuntimeTexts.payloadLine(entry),
    ).joinToString("  ")

/**
 * The event injector: the domains, declared ones first and highlighted, then those only the state machines use,
 * marked; the events of the chosen domain with their payload types; the JSON payload prefilled with the template of the
 * event's payload type, with its findings after every edit; whether events can be injected; Send, enabled only when the
 * payload has no error and injection is available; and the outcome of the last send.
 */
@Composable
private fun EventInjector(injector: InjectorState) {
    val ui by injector.state.collectAsState()
    val colors = KarpfenTheme.colors
    Column(Modifier.fillMaxWidth().height(INJECTOR_HEIGHT.dp).verticalScroll(rememberScrollState()).padding(8.dp)) {
        Text("Inject an event", color = colors.text, style = KarpfenTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(ui.domains, key = { it.name }) { domain ->
                val declared = domain.origin == DomainOrigin.DECLARED
                val weight = if (declared) FontWeight.SemiBold else FontWeight.Normal
                FilterChip(
                    selected = ui.domain == domain.name,
                    onClick = { injector.selectDomain(domain.name) },
                    label = {
                        Text(
                            if (declared) domain.name else "${domain.name} (used only)",
                            style = KarpfenTheme.typography.uiSmall.copy(fontWeight = weight),
                        )
                    },
                    modifier = Modifier.testTag(RuntimeViewTestTags.domain(domain.name)),
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(ui.events, key = { it.name }) { event ->
                val label = when {
                    !event.declared -> "${event.name} (used only)"
                    event.payloadType != null -> "${event.name}: ${event.payloadType}"
                    else -> event.name
                }
                FilterChip(
                    selected = ui.event?.name == event.name,
                    onClick = { injector.selectEvent(event.name) },
                    label = { Text(label, style = KarpfenTheme.typography.uiSmall) },
                    modifier = Modifier.testTag(RuntimeViewTestTags.injectable(event.name)),
                )
            }
        }
        if (ui.event != null && ui.kind == PayloadKind.JSON) {
            OutlinedTextField(
                value = ui.text,
                onValueChange = injector::edit,
                textStyle = KarpfenTheme.typography.monospace,
                minLines = PAYLOAD_LINES,
                modifier = Modifier.fillMaxWidth().testTag(RuntimeViewTestTags.PAYLOAD),
            )
        } else if (ui.event != null) {
            Text(NO_PAYLOAD, color = colors.textMuted, style = KarpfenTheme.typography.uiSmall)
        }
        ui.validation.diagnostics.forEachIndexed { index, diagnostic ->
            Text(
                diagnostic.message,
                color = if (diagnostic.severity == Severity.ERROR) colors.error else colors.warning,
                style = KarpfenTheme.typography.uiSmall,
                modifier = Modifier.testTag(RuntimeViewTestTags.diagnostic(index)),
            )
        }
        Text(
            "Injection: ${RuntimeTexts.availability(ui.availability)}",
            color = colors.textMuted,
            style = KarpfenTheme.typography.uiSmall,
            modifier = Modifier.testTag(RuntimeViewTestTags.AVAILABILITY),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { injector.send() }, enabled = ui.canSend, modifier = Modifier.testTag(RuntimeViewTestTags.SEND)) {
                Text("Send", style = KarpfenTheme.typography.uiSmall)
            }
            ui.lastOutcome?.let {
                Text(
                    RuntimeTexts.outcome(it),
                    color = colors.textMuted,
                    style = KarpfenTheme.typography.uiSmall,
                    modifier = Modifier.testTag(RuntimeViewTestTags.OUTCOME),
                )
            }
        }
    }
}

/** What the injector says instead of the payload editor for an event without a payload type. */
private const val NO_PAYLOAD = "The event has no payload type; it is sent without a payload."

/** The height of the injector in dp. */
private const val INJECTOR_HEIGHT = 260

/** The lines the payload editor shows at least. */
private const val PAYLOAD_LINES = 4
