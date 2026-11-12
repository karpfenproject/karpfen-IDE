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
import androidx.compose.foundation.layout.width
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
import io.karpfen.ide.runtime.session.events.InjectionOutcome
import io.karpfen.ide.runtime.session.events.PayloadKind
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.runtime.DomainEventEntry
import io.karpfen.ide.ui.workbench.runtime.InjectorState
import io.karpfen.ide.ui.workbench.runtime.RunDetails
import io.karpfen.ide.ui.workbench.runtime.RuntimeTexts

/**
 * The Events pane of a launched run. It first shows the form that sends an event into the environment, as [SendEvent]
 * shows it. Below the form it lists the events the environment has published, as [SeenEvents] shows them. The pane
 * scrolls as a whole. In a low pane the form with its Send button comes first, and the list is one scroll away. In a
 * tall pane both are in sight, so an event that was sent shows up right below the form.
 */
@Composable
internal fun EventsPane(details: RunDetails) {
    val events by details.events.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) { PaneTitle("Events") }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            SendEvent(details.injector)
            Box(Modifier.fillMaxWidth().height(1.dp).background(KarpfenTheme.colors.border))
            SeenEvents(details, events)
        }
    }
}

/**
 * The form that sends an event. It starts with a sentence on what sending does. Then it leads through the three
 * choices in the order they are made, one per row. The domains come first, the declared ones in bold and those that
 * only the state machines use marked as such. The events of the chosen domain follow, with their payload types. Then
 * comes the JSON payload, which starts as the template of the event's payload type, with the findings on it after
 * every edit.
 *
 * The Send button names the event it sends. It is enabled only when the payload has no error and events can be sent.
 * The line next to it says what the last send did, and the line below says whether events can be sent now and why
 * not.
 */
@Composable
private fun SendEvent(injector: InjectorState) {
    val ui by injector.state.collectAsState()
    val colors = KarpfenTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(
            SEND_HELP,
            color = colors.textMuted,
            style = KarpfenTheme.typography.uiSmall,
            modifier = Modifier.testTag(RuntimeViewTestTags.SEND_HELP),
        )
        Step("1. Domain") {
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
        }
        Step("2. Event") {
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
        }
        Step("3. Payload") {
            if (ui.event != null && ui.kind == PayloadKind.JSON) {
                OutlinedTextField(
                    value = ui.text,
                    onValueChange = injector::edit,
                    textStyle = KarpfenTheme.typography.monospace,
                    minLines = PAYLOAD_LINES,
                    modifier = Modifier.fillMaxWidth().testTag(RuntimeViewTestTags.PAYLOAD),
                )
            } else {
                Text(if (ui.event == null) NO_EVENT else NO_PAYLOAD, color = colors.textMuted, style = KarpfenTheme.typography.uiSmall)
            }
        }
        ui.validation.diagnostics.forEachIndexed { index, diagnostic ->
            Text(
                diagnostic.message,
                color = if (diagnostic.severity == Severity.ERROR) colors.error else colors.warning,
                style = KarpfenTheme.typography.uiSmall,
                modifier = Modifier.testTag(RuntimeViewTestTags.diagnostic(index)),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { injector.send() }, enabled = ui.canSend, modifier = Modifier.testTag(RuntimeViewTestTags.SEND)) {
                Text(ui.event?.let { "Send ${it.name}" } ?: "Send", style = KarpfenTheme.typography.uiSmall)
            }
            ui.lastOutcome?.let {
                Text(
                    RuntimeTexts.outcome(it),
                    color = if (it is InjectionOutcome.Sent) colors.textMuted else colors.error,
                    style = KarpfenTheme.typography.uiSmall,
                    modifier = Modifier.testTag(RuntimeViewTestTags.OUTCOME),
                )
            }
        }
        Text(
            RuntimeTexts.availability(ui.availability),
            color = colors.textMuted,
            style = KarpfenTheme.typography.uiSmall,
            modifier = Modifier.padding(bottom = 4.dp).testTag(RuntimeViewTestTags.AVAILABILITY),
        )
    }
}

/** One step of the form: its [label] in a column of fixed width, and the [content] of the step next to it. */
@Composable
private fun Step(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = KarpfenTheme.colors.text,
            style = KarpfenTheme.typography.uiSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.width(STEP_LABEL_WIDTH.dp),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

/**
 * The events the environment has published, newest first, under a line that names the columns. These are the events
 * sent from here and from other clients, and the events the state machines raise themselves. Each event takes one
 * line with its time, domain, name, source and payload, in columns. The list has a fixed height and scrolls on its
 * own. A click on an event shows its whole payload below the list. While there is no event, the pane says what will
 * appear.
 */
@Composable
private fun SeenEvents(details: RunDetails, events: List<DomainEventEntry>) {
    var chosen by remember(details) { mutableStateOf<Long?>(null) }
    val colors = KarpfenTheme.colors
    val columns = remember(events) { EventColumns.of(events) }
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp)) { Heading("Events in this environment, newest first") }
        if (events.isEmpty()) {
            Text(
                NO_EVENTS,
                color = colors.textMuted,
                style = KarpfenTheme.typography.uiSmall,
                modifier = Modifier.padding(horizontal = 8.dp).testTag(RuntimeViewTestTags.NO_EVENTS),
            )
            return@Column
        }
        Text(
            columns.header(),
            color = colors.textMuted,
            style = KarpfenTheme.typography.monospace,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().height(EVENT_LIST_HEIGHT.dp)) {
            items(events, key = { it.sequence }) { entry ->
                Text(
                    columns.line(entry),
                    color = colors.text,
                    style = KarpfenTheme.typography.monospace,
                    maxLines = 1,
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
    }
}

/**
 * The columns of the event list. The time, the domain, the event name and the source are as wide as their longest
 * entry, so the lines form a table in the monospace font. The payload comes last and takes the rest.
 */
private class EventColumns(private val time: Int, private val domain: Int, private val name: Int, private val source: Int) {

    /** The line that names the columns. */
    fun header(): String = row("Time", "Domain", "Event", "Source", "Payload")

    /** The line of [entry]. */
    fun line(entry: DomainEventEntry): String = row(
        RuntimeTexts.time(entry),
        entry.event.domain,
        RuntimeTexts.eventName(entry),
        RuntimeTexts.source(entry),
        RuntimeTexts.payloadLine(entry),
    )

    private fun row(time: String, domain: String, name: String, source: String, payload: String): String =
        listOf(time.padEnd(this.time), domain.padEnd(this.domain), name.padEnd(this.name), source.padEnd(this.source), payload)
            .joinToString("  ")
            .trimEnd()

    companion object {
        /** The columns that fit [events] and the column names. */
        fun of(events: List<DomainEventEntry>): EventColumns = EventColumns(
            time = width("Time", events) { RuntimeTexts.time(it) },
            domain = width("Domain", events) { it.event.domain },
            name = width("Event", events) { RuntimeTexts.eventName(it) },
            source = width("Source", events) { RuntimeTexts.source(it) },
        )

        private fun width(title: String, events: List<DomainEventEntry>, text: (DomainEventEntry) -> String): Int =
            maxOf(title.length, events.maxOfOrNull { text(it).length } ?: 0)
    }
}

/** What the form says about sending an event. */
private const val SEND_HELP =
    "Send puts an event into the running environment. A state machine that waits for it takes it on its next tick, and " +
        "the Model and Log panes show what it does. An event that no state machine takes expires."

/** What the form says instead of the payload editor while no event is chosen. */
private const val NO_EVENT = "Choose an event first."

/** What the form says instead of the payload editor for an event without a payload type. */
private const val NO_PAYLOAD = "This event has no payload type. It is sent without a payload."

/** What the event list says while it is empty. */
private const val NO_EVENTS = "No events yet. Events you send and events the state machines raise appear here."

/** The width of the labels of the form's steps in dp. */
private const val STEP_LABEL_WIDTH = 76

/** The height of the event list in dp. */
private const val EVENT_LIST_HEIGHT = 180

/** The lines the payload editor shows at least. */
private const val PAYLOAD_LINES = 3
