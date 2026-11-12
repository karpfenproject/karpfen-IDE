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
package io.karpfen.ide.ui.workbench.bridges.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.karpfen.ide.bridges.api.BridgeKind
import io.karpfen.ide.bridges.api.FlowPolicy
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.bridges.BridgeDraft
import io.karpfen.ide.ui.workbench.bridges.BridgeDrafts
import io.karpfen.ide.ui.workbench.bridges.BridgeFormState
import io.karpfen.ide.ui.workbench.bridges.BridgeSetting
import io.karpfen.ide.ui.workbench.bridges.FieldDraft
import io.karpfen.ide.ui.workbench.bridges.Follows
import io.karpfen.ide.ui.workbench.bridges.InboundDraft
import io.karpfen.ide.ui.workbench.bridges.OutboundDraft
import io.karpfen.ide.ui.workbench.bridges.PolicyDraft
import io.karpfen.ide.ui.workbench.bridges.PolicyKind

/**
 * Shows the bridge [form] over the workbench, on a scrim that swallows all pointer input, and nothing while [form] is
 * null. The form has the kind of the bridge, the settings of that kind, the inbound routes and the outbound routes.
 * Every change goes into the draft of the form at once. Below the scrolling part, the form lists what keeps it from
 * being saved, and Save is enabled once that list is empty. [onSave] and [onClose] are the two buttons.
 */
@Composable
internal fun BridgeFormLayer(form: BridgeFormState?, onSave: () -> Unit, onClose: () -> Unit) {
    if (form == null) return
    val draft by form.draft.collectAsState()
    val problems by form.problems.collectAsState()
    val colors = KarpfenTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .pointerInput(form) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = FORM_WIDTH.dp)
                .fillMaxWidth(FORM_SHARE)
                .fillMaxHeight(FORM_SHARE)
                .background(colors.panel, RoundedCornerShape(6.dp))
                .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                .padding(16.dp)
                .testTag(BridgeFormTestTags.FORM),
        ) {
            Text(
                if (form.isNew) "New bridge ${form.name}" else "Bridge ${form.name}",
                color = colors.text,
                style = KarpfenTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold),
            )
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (form.fileProblems.isNotEmpty()) {
                    Section("The file had problems. What could not be read is left out, and saving writes the file without it.")
                    form.fileProblems.forEach { Note(it, colors.warning) }
                }
                KindAndSettings(draft, form::edit)
                Section("Messages that become events")
                draft.inbound.forEachIndexed { index, route -> InboundCard(index, route, draft.kind, form::edit) }
                TextButton(
                    onClick = { form.edit { it.copy(inbound = it.inbound + InboundDraft()) } },
                    modifier = Modifier.testTag(BridgeFormTestTags.ADD_INBOUND),
                ) { Text("Add inbound route") }
                Section("What the model reports")
                draft.outbound.forEachIndexed { index, route -> OutboundCard(index, route, draft.kind, form::edit) }
                TextButton(
                    onClick = { form.edit { it.copy(outbound = it.outbound + OutboundDraft()) } },
                    modifier = Modifier.testTag(BridgeFormTestTags.ADD_OUTBOUND),
                ) { Text("Add outbound route") }
            }
            if (problems.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = PROBLEMS_HEIGHT.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag(BridgeFormTestTags.PROBLEMS),
                ) { problems.forEach { Note(it, colors.error) } }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Button(onClick = onSave, enabled = problems.isEmpty(), modifier = Modifier.testTag(BridgeFormTestTags.SAVE)) { Text("Save") }
                OutlinedButton(onClick = onClose, modifier = Modifier.testTag(BridgeFormTestTags.CANCEL)) { Text("Cancel") }
            }
        }
    }
}

/** The kind of the bridge as chips and the settings of that kind as fields and boxes. */
@Composable
private fun KindAndSettings(draft: BridgeDraft, edit: ((BridgeDraft) -> BridgeDraft) -> Unit) {
    Section("Kind")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BridgeKind.entries.filter { it != BridgeKind.ROS || draft.kind == BridgeKind.ROS }.forEach { kind ->
            Chip(kind.id.uppercase(), draft.kind == kind, BridgeFormTestTags.kind(kind.id)) { edit { BridgeDrafts.withKind(it, kind) } }
        }
    }
    if (draft.kind == BridgeKind.ROS) Note("The IDE cannot run ROS bridges yet.", KarpfenTheme.colors.warning)
    val settings = BridgeDrafts.settingsOf(draft.kind)
    if (settings.isEmpty()) return
    Section("Settings")
    settings.forEach { setting -> SettingRow(setting, draft.settings[setting.key].orEmpty()) { value -> edit { it.withSetting(setting.key, value) } } }
}

/** One setting: a box to tick for a flag, a labelled field for anything else. */
@Composable
private fun SettingRow(setting: BridgeSetting, value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (setting.isFlag) {
            val on = value.trim().equals("true", ignoreCase = true)
            Row(
                Modifier.clickable { onChange(if (on) "" else "true") }.testTag(BridgeFormTestTags.setting(setting.key)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = on, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Label(setting.label)
            }
        } else {
            Label(setting.label, Modifier.width(LABEL_WIDTH.dp))
            Field(value, onChange, setting.hint, Modifier.weight(1f), BridgeFormTestTags.setting(setting.key), setting.isSecret)
        }
    }
}

/** The inbound route at [index]: where a message arrives, the event it becomes, its payload and its flow policy. */
@Composable
private fun InboundCard(index: Int, route: InboundDraft, kind: BridgeKind, edit: ((BridgeDraft) -> BridgeDraft) -> Unit) {
    val change: ((InboundDraft) -> InboundDraft) -> Unit = { transform -> edit { it.copy(inbound = it.inbound.replaced(index, transform)) } }
    Card("Inbound route ${index + 1}", onRemove = { edit { it.copy(inbound = it.inbound.without(index)) } }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("From")
            Field(
                route.from, { value -> change { it.copy(from = value) } },
                if (kind == BridgeKind.HTTP) "/path" else "topic/filter", Modifier.weight(1f), BridgeFormTestTags.inbound(index, "from"),
            )
            Label("becomes the event")
            Field(route.event, { value -> change { it.copy(event = value) } }, "domain/name", Modifier.weight(1f), BridgeFormTestTags.inbound(index, "event"))
        }
        Row(
            Modifier.clickable { change { it.copy(passBody = !it.passBody) } }.testTag(BridgeFormTestTags.inbound(index, "passBody")),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = route.passBody, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Label("The body of the message is the payload of the event")
        }
        if (!route.passBody) {
            route.fields.forEachIndexed { at, field ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("Field")
                    Field(
                        field.name, { value -> change { it.copy(fields = it.fields.replaced(at) { old -> old.copy(name = value) }) } },
                        "name", Modifier.weight(1f), BridgeFormTestTags.payload(index, at, "name"),
                    )
                    Label("is")
                    Field(
                        field.value, { value -> change { it.copy(fields = it.fields.replaced(at) { old -> old.copy(value = value) }) } },
                        "a value, or a path such as \$.bright", Modifier.weight(1f), BridgeFormTestTags.payload(index, at, "value"),
                    )
                    TextButton(onClick = { change { it.copy(fields = it.fields.without(at)) } }) { Text("Remove") }
                }
            }
            TextButton(
                onClick = { change { it.copy(fields = it.fields + FieldDraft()) } },
                modifier = Modifier.testTag(BridgeFormTestTags.inbound(index, "addField")),
            ) { Text("Add payload field") }
        }
        Policy(route.policy, BridgeFormTestTags.inbound(index, "number")) { policy -> change { it.copy(policy = policy) } }
    }
}

/** The outbound route at [index]: what it follows, where the message goes and its flow policy. */
@Composable
private fun OutboundCard(index: Int, route: OutboundDraft, kind: BridgeKind, edit: ((BridgeDraft) -> BridgeDraft) -> Unit) {
    val change: ((OutboundDraft) -> OutboundDraft) -> Unit = { transform -> edit { it.copy(outbound = it.outbound.replaced(index, transform)) } }
    Card("Outbound route ${index + 1}", onRemove = { edit { it.copy(outbound = it.outbound.without(index)) } }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("Follows")
            Follows.entries.forEach { follows ->
                Chip(follows.title, route.follows == follows, BridgeFormTestTags.outbound(index, follows.name.lowercase())) {
                    change { it.copy(follows = follows) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(route.what, { value -> change { it.copy(what = value) } }, route.follows.hint, Modifier.weight(1f), BridgeFormTestTags.outbound(index, "what"))
            Label("goes to")
            Field(
                route.to, { value -> change { it.copy(to = value) } },
                if (kind == BridgeKind.HTTP) "http://host:port/path" else "topic", Modifier.weight(1f), BridgeFormTestTags.outbound(index, "to"),
            )
        }
        Policy(route.policy, BridgeFormTestTags.outbound(index, "number")) { policy -> change { it.copy(policy = policy) } }
    }
}

/** The flow policy of a route as chips, with the field for its number where the policy has one. */
@Composable
private fun Policy(policy: PolicyDraft, numberTag: String, onChange: (PolicyDraft) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("When messages come too fast")
        PolicyKind.entries.forEach { kind ->
            Chip(kind.title, policy.kind == kind, "$numberTag.${kind.name.lowercase()}") { onChange(PolicyDraft(kind)) }
        }
        when (policy.kind) {
            PolicyKind.QUEUE -> {
                Label("of at most")
                Field(
                    policy.number, { onChange(policy.copy(number = it)) },
                    FlowPolicy.DEFAULT_LIMIT.toString(), Modifier.width(NUMBER_WIDTH.dp), numberTag,
                )
                Label("messages")
            }
            PolicyKind.SAMPLE -> {
                Label("of")
                Field(policy.number, { onChange(policy.copy(number = it)) }, "500", Modifier.width(NUMBER_WIDTH.dp), numberTag)
                Label("ms")
            }
            PolicyKind.LATEST -> Unit
        }
    }
}

/** A bordered group of the form with its [title] and a button that removes it. */
@Composable
private fun Card(title: String, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val colors = KarpfenTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, colors.border, RoundedCornerShape(4.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = colors.textMuted, style = KarpfenTheme.typography.uiSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onRemove) { Text("Remove") }
        }
        content()
    }
}

/** A heading of a part of the form. */
@Composable
private fun Section(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, color = KarpfenTheme.colors.text, style = KarpfenTheme.typography.uiSmall.copy(fontWeight = FontWeight.SemiBold))
    Spacer(Modifier.height(4.dp))
}

/** One line of a note in [color]. */
@Composable
private fun Note(text: String, color: Color) {
    Text(text, color = color, style = KarpfenTheme.typography.uiSmall)
}

/** The name of a field next to it. */
@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, color = KarpfenTheme.colors.text, style = KarpfenTheme.typography.uiSmall, modifier = modifier)
}

/** A chip that is [selected] or not and calls [onClick]. */
@Composable
private fun Chip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = KarpfenTheme.typography.uiSmall) },
        modifier = Modifier.testTag(tag),
    )
}

/**
 * A one-line text field with a border. While it is empty it shows [hint]. It keeps the text it shows itself, so typing
 * never waits for the draft, and takes over [value] whenever the draft says something else, as after a route above it
 * was removed.
 */
@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier, tag: String, secret: Boolean = false) {
    val colors = KarpfenTheme.colors
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != text) text = value }
    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        singleLine = true,
        textStyle = KarpfenTheme.typography.ui.copy(color = colors.text),
        cursorBrush = SolidColor(colors.text),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.testTag(tag),
        decorationBox = { inner ->
            Box(Modifier.border(1.dp, colors.border, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 4.dp)) {
                if (text.isEmpty()) Text(hint, color = colors.textMuted, style = KarpfenTheme.typography.ui, maxLines = 1)
                inner()
            }
        },
    )
}

/** These settings with [key] set to [value], or without it if [value] is blank. */
private fun BridgeDraft.withSetting(key: String, value: String): BridgeDraft =
    copy(settings = if (value.isBlank()) settings - key else settings + (key to value))

/** This list with the element at [index] replaced by what [transform] makes of it. */
private fun <T> List<T>.replaced(index: Int, transform: (T) -> T): List<T> = mapIndexed { at, item -> if (at == index) transform(item) else item }

/** This list without the element at [index]. */
private fun <T> List<T>.without(index: Int): List<T> = filterIndexed { at, _ -> at != index }

/** What the chip of this choice says. */
private val Follows.title: String
    get() = when (this) {
        Follows.PROPERTY -> "A property"
        Follows.STATE -> "Active states"
        Follows.EVENT -> "An event"
    }

/** What the empty field of the followed thing shows for this choice. */
private val Follows.hint: String
    get() = when (this) {
        Follows.PROPERTY -> "object/property"
        Follows.STATE -> "the object of the state machine"
        Follows.EVENT -> "domain/name"
    }

/** What the chip of this policy says. */
private val PolicyKind.title: String
    get() = when (this) {
        PolicyKind.QUEUE -> "Queue"
        PolicyKind.LATEST -> "Keep the latest"
        PolicyKind.SAMPLE -> "One per period"
    }

/** The greatest width of the form in dp. */
private const val FORM_WIDTH = 860

/** The share of the window the form takes at most, in each direction. */
private const val FORM_SHARE = 0.9f

/** The greatest height of the list of problems in dp. A longer list scrolls. */
private const val PROBLEMS_HEIGHT = 96

/** The width of the labels of the settings in dp. */
private const val LABEL_WIDTH = 220

/** The width of the number field of a flow policy in dp. */
private const val NUMBER_WIDTH = 72
