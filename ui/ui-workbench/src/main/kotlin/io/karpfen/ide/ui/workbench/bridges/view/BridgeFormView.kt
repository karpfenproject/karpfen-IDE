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

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.karpfen.ide.bridges.api.BridgeKind
import io.karpfen.ide.bridges.api.FlowPolicy
import io.karpfen.ide.bridges.mapping.BridgeConfigFile
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.bridges.BridgeDraft
import io.karpfen.ide.ui.workbench.bridges.BridgeDrafts
import io.karpfen.ide.ui.workbench.bridges.BridgeFormState
import io.karpfen.ide.ui.workbench.bridges.BridgeSetting
import io.karpfen.ide.ui.workbench.bridges.BridgeSuggestions
import io.karpfen.ide.ui.workbench.bridges.FieldDraft
import io.karpfen.ide.ui.workbench.bridges.Follows
import io.karpfen.ide.ui.workbench.bridges.InboundDraft
import io.karpfen.ide.ui.workbench.bridges.OutboundDraft
import io.karpfen.ide.ui.workbench.bridges.PolicyDraft
import io.karpfen.ide.ui.workbench.bridges.PolicyKind

/**
 * Shows the bridge [form] over the workbench, on a scrim that swallows all pointer input, and nothing while [form] is
 * null. The form has the name of the bridge, its kind, the settings of that kind, the inbound routes and the outbound
 * routes. A changed name renames the file when the form is saved.
 * The fields for an event, a property and a state machine have a button that lists those of the project to pick from.
 * A route of a ROS bridge also has the type of its messages.
 * Every change goes into the draft of the form at once. Below the scrolling part, the form lists what keeps it from
 * being saved, and Save is enabled once that list is empty. [onSave] and [onClose] are the two buttons.
 *
 * The form is laid out as a grid of rows of one height. Every row starts with its label in a column of one width, so
 * the fields of all rows begin at the same place. Two fields that belong together share a row, each with half of the
 * width. The controls are small and rectangular, like those of the rest of the workbench.
 *
 * Every control that takes a click has an interaction source of its own. A control without one creates it at its
 * first press, and under the scrim, which consumes every pointer event, that first press is lost.
 */
@Composable
internal fun BridgeFormLayer(form: BridgeFormState?, onSave: () -> Unit, onClose: () -> Unit) {
    if (form == null) return
    val draft by form.draft.collectAsState()
    val name by form.name.collectAsState()
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
                .background(colors.panel, RoundedCornerShape(CORNER.dp))
                .border(1.dp, colors.border, RoundedCornerShape(CORNER.dp))
                .padding(12.dp)
                .testTag(BridgeFormTestTags.FORM),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP.dp),
        ) {
            Text(
                if (form.isNew) "New bridge" else "Bridge",
                color = colors.text,
                style = KarpfenTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold),
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP.dp),
            ) {
                FormRow("Name") {
                    Field(name, form::rename, "the name of the bridge", Modifier.weight(1f), BridgeFormTestTags.NAME)
                    Label(BridgeConfigFile.SUFFIX, muted = true)
                }
                KindAndSettings(draft, form::edit)
                if (form.fileProblems.isNotEmpty()) {
                    Section("The file had problems. What could not be read is left out, and saving writes the file without it.")
                    form.fileProblems.forEach { Note(it, colors.warning) }
                }
                Section("Messages that become events") {
                    LinkButton("Add inbound route", BridgeFormTestTags.ADD_INBOUND) { form.edit { it.copy(inbound = it.inbound + InboundDraft()) } }
                }
                draft.inbound.forEachIndexed { index, route -> InboundCard(index, route, draft.kind, form.suggestions, form::edit) }
                Section("What the model reports") {
                    LinkButton("Add outbound route", BridgeFormTestTags.ADD_OUTBOUND) { form.edit { it.copy(outbound = it.outbound + OutboundDraft()) } }
                }
                draft.outbound.forEachIndexed { index, route -> OutboundCard(index, route, draft.kind, form.suggestions, form::edit) }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP.dp, Alignment.End)) {
                FormButton("Save", BridgeFormTestTags.SAVE, enabled = problems.isEmpty(), isDefault = true, onClick = onSave)
                FormButton("Cancel", BridgeFormTestTags.CANCEL, onClick = onClose)
            }
        }
    }
}

/** The kind of the bridge as a choice and the settings of that kind, one row each. */
@Composable
private fun KindAndSettings(draft: BridgeDraft, edit: ((BridgeDraft) -> BridgeDraft) -> Unit) {
    FormRow("Kind") {
        Choice(BridgeKind.entries, draft.kind, { it.id.uppercase() }, { BridgeFormTestTags.kind(it.id) }) { kind ->
            edit { BridgeDrafts.withKind(it, kind) }
        }
    }
    BridgeDrafts.settingsOf(draft.kind).forEach { setting ->
        SettingRow(setting, draft.settings[setting.key].orEmpty()) { value -> edit { it.withSetting(setting.key, value) } }
    }
}

/**
 * One setting. A flag is a box to tick with its text, in the column of the fields. Anything else is a labelled field.
 */
@Composable
private fun SettingRow(setting: BridgeSetting, value: String, onChange: (String) -> Unit) {
    if (setting.isFlag) {
        val on = value.trim().equals("true", ignoreCase = true)
        FormRow("") { TickBox(on, setting.label, BridgeFormTestTags.setting(setting.key)) { onChange(if (it) "true" else "") } }
    } else {
        FormRow(setting.label) {
            Field(value, onChange, setting.hint, Modifier.weight(1f), BridgeFormTestTags.setting(setting.key), setting.isSecret)
        }
    }
}

/** The inbound route at [index]: where a message arrives, the event it becomes, its payload and its flow policy. */
@Composable
private fun InboundCard(
    index: Int,
    route: InboundDraft,
    kind: BridgeKind,
    suggestions: BridgeSuggestions,
    edit: ((BridgeDraft) -> BridgeDraft) -> Unit,
) {
    val change: ((InboundDraft) -> InboundDraft) -> Unit = { transform -> edit { it.copy(inbound = it.inbound.replaced(index, transform)) } }
    Card("Inbound route ${index + 1}", BridgeFormTestTags.inbound(index, "remove"), onRemove = { edit { it.copy(inbound = it.inbound.without(index)) } }) {
        FormRow("From") {
            Field(
                route.from, { value -> change { it.copy(from = value) } },
                fromHint(kind), Modifier.weight(1f), BridgeFormTestTags.inbound(index, "from"),
            )
            Label("Event", Modifier.width(SECOND_LABEL_WIDTH.dp))
            PickField(
                route.event, { value -> change { it.copy(event = value) } },
                "domain/name", suggestions.events, Modifier.weight(1f), BridgeFormTestTags.inbound(index, "event"),
            )
        }
        if (kind == BridgeKind.ROS) {
            MessageType(route.type, "found by ROS if the topic exists", BridgeFormTestTags.inbound(index, "type")) { value ->
                change { it.copy(type = value) }
            }
        }
        FormRow("Payload") {
            TickBox(route.passBody, "The body of the message", BridgeFormTestTags.inbound(index, "passBody")) { on ->
                change { it.copy(passBody = on) }
            }
            if (!route.passBody) {
                Spacer(Modifier.weight(1f))
                LinkButton("Add payload field", BridgeFormTestTags.inbound(index, "addField")) {
                    change { it.copy(fields = it.fields + FieldDraft()) }
                }
            }
        }
        if (!route.passBody) {
            route.fields.forEachIndexed { at, field ->
                FormRow("Field ${at + 1}") {
                    Field(
                        field.name, { value -> change { it.copy(fields = it.fields.replaced(at) { old -> old.copy(name = value) }) } },
                        "name", Modifier.weight(1f), BridgeFormTestTags.payload(index, at, "name"),
                    )
                    Label("is", Modifier.width(SECOND_LABEL_WIDTH.dp))
                    Field(
                        field.value, { value -> change { it.copy(fields = it.fields.replaced(at) { old -> old.copy(value = value) }) } },
                        "a value, or a path such as \$.bright", Modifier.weight(1f), BridgeFormTestTags.payload(index, at, "value"),
                    )
                    LinkButton("Remove", BridgeFormTestTags.payload(index, at, "remove")) { change { it.copy(fields = it.fields.without(at)) } }
                }
            }
        }
        Policy(route.policy, BridgeFormTestTags.inbound(index, "number")) { policy -> change { it.copy(policy = policy) } }
    }
}

/** The outbound route at [index]: what it follows, where the message goes and its flow policy. */
@Composable
private fun OutboundCard(
    index: Int,
    route: OutboundDraft,
    kind: BridgeKind,
    suggestions: BridgeSuggestions,
    edit: ((BridgeDraft) -> BridgeDraft) -> Unit,
) {
    val change: ((OutboundDraft) -> OutboundDraft) -> Unit = { transform -> edit { it.copy(outbound = it.outbound.replaced(index, transform)) } }
    Card("Outbound route ${index + 1}", BridgeFormTestTags.outbound(index, "remove"), onRemove = { edit { it.copy(outbound = it.outbound.without(index)) } }) {
        FormRow("Follows") {
            Choice(Follows.entries, route.follows, { it.title }, { BridgeFormTestTags.outbound(index, it.name.lowercase()) }) { follows ->
                change { it.copy(follows = follows) }
            }
        }
        FormRow(route.follows.label) {
            PickField(
                route.what, { value -> change { it.copy(what = value) } },
                route.follows.hint, suggestions.followed(route.follows), Modifier.weight(1f), BridgeFormTestTags.outbound(index, "what"),
            )
            Label("To", Modifier.width(SECOND_LABEL_WIDTH.dp))
            Field(
                route.to, { value -> change { it.copy(to = value) } },
                toHint(kind), Modifier.weight(1f), BridgeFormTestTags.outbound(index, "to"),
            )
        }
        if (kind == BridgeKind.ROS) {
            MessageType(route.type, "std_msgs/msg/String", BridgeFormTestTags.outbound(index, "type")) { value ->
                change { it.copy(type = value) }
            }
        }
        Policy(route.policy, BridgeFormTestTags.outbound(index, "number")) { policy -> change { it.copy(policy = policy) } }
    }
}

/** The type of the ROS messages of a route, to type in or to pick from the standard types. */
@Composable
private fun MessageType(value: String, hint: String, tag: String, onChange: (String) -> Unit) {
    FormRow("Message type") { PickField(value, onChange, hint, BridgeDrafts.messageTypes, Modifier.weight(1f), tag) }
}

/** The flow policy of a route as a choice, with the field for its number where the policy has one. */
@Composable
private fun Policy(policy: PolicyDraft, numberTag: String, onChange: (PolicyDraft) -> Unit) {
    FormRow("If too fast") {
        Choice(PolicyKind.entries, policy.kind, { it.title }, { "$numberTag.${it.name.lowercase()}" }) { kind -> onChange(PolicyDraft(kind)) }
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

/**
 * One row of the form. Its [label] stands in the label column, which has the same width in every row, and [content]
 * fills the rest. So the fields of all rows start at the same place.
 */
@Composable
private fun FormRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT.dp),
        horizontalArrangement = Arrangement.spacedBy(GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(label, Modifier.width(LABEL_WIDTH.dp))
        content()
    }
}

/** A bordered group of rows with its [title] and a button that removes it, which carries [removeTag]. */
@Composable
private fun Card(title: String, removeTag: String, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val colors = KarpfenTheme.colors
    Column(
        Modifier.fillMaxWidth().border(1.dp, colors.border, RoundedCornerShape(CORNER.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = colors.textMuted, style = KarpfenTheme.typography.uiSmall, modifier = Modifier.weight(1f))
            LinkButton("Remove", removeTag, onClick = onRemove)
        }
        content()
    }
}

/**
 * The heading of a part of the form, with what [trailing] puts at its right end, such as a button that adds to the
 * part.
 */
@Composable
private fun Section(text: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = SECTION_GAP.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            color = KarpfenTheme.colors.text,
            style = KarpfenTheme.typography.uiSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/** One line of a note in [color]. */
@Composable
private fun Note(text: String, color: Color) {
    Text(text, color = color, style = KarpfenTheme.typography.uiSmall)
}

/** A short text next to a field, [muted] where it only adds to what the field says. */
@Composable
private fun Label(text: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    val colors = KarpfenTheme.colors
    Text(text, color = if (muted) colors.textMuted else colors.text, style = KarpfenTheme.typography.uiSmall, maxLines = 1, modifier = modifier)
}

/**
 * A choice of one of [options], drawn as one bordered strip with a part for each. The part of [selected] is filled.
 * [title] says what a part reads and [tag] gives its test tag.
 */
@Composable
private fun <T> Choice(options: List<T>, selected: T, title: (T) -> String, tag: (T) -> String, onSelect: (T) -> Unit) {
    val colors = KarpfenTheme.colors
    Row(Modifier.height(CONTROL_HEIGHT.dp).border(1.dp, colors.border, RoundedCornerShape(CORNER.dp))) {
        options.forEachIndexed { index, option ->
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border))
            Box(
                Modifier
                    .fillMaxHeight()
                    .background(if (option == selected) colors.selection else Color.Transparent)
                    .selectable(option == selected, remember { MutableInteractionSource() }, LocalIndication.current, role = Role.RadioButton) {
                        onSelect(option)
                    }
                    .padding(horizontal = 10.dp)
                    .testTag(tag(option)),
                contentAlignment = Alignment.Center,
            ) { Label(title(option)) }
        }
    }
}

/** A box to tick with its [text] behind it. A click on either changes it and reports the new value to [onChange]. */
@Composable
private fun TickBox(checked: Boolean, text: String, tag: String, onChange: (Boolean) -> Unit) {
    val colors = KarpfenTheme.colors
    Row(
        Modifier
            .height(CONTROL_HEIGHT.dp)
            .toggleable(checked, remember { MutableInteractionSource() }, LocalIndication.current, role = Role.Checkbox, onValueChange = onChange)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(TICK_SIZE.dp).border(1.dp, if (checked) colors.accent else colors.border, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Box(Modifier.size((TICK_SIZE - 6).dp).background(colors.accent, RoundedCornerShape(1.dp)))
        }
        Label(text)
    }
}

/**
 * A button of the form, as high as a field. The default button of the form is drawn in the accent colour. A button
 * that is not [enabled] is greyed out and takes no click.
 */
@Composable
private fun FormButton(text: String, tag: String, enabled: Boolean = true, isDefault: Boolean = false, onClick: () -> Unit) {
    val colors = KarpfenTheme.colors
    val color = when {
        !enabled -> colors.textMuted
        isDefault -> colors.accent
        else -> colors.text
    }
    Box(
        Modifier
            .height(CONTROL_HEIGHT.dp)
            .widthIn(min = BUTTON_WIDTH.dp)
            .border(1.dp, if (enabled && isDefault) colors.accent else colors.border, RoundedCornerShape(CORNER.dp))
            .clickable(remember { MutableInteractionSource() }, LocalIndication.current, enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, style = KarpfenTheme.typography.uiSmall.copy(fontWeight = if (isDefault) FontWeight.SemiBold else FontWeight.Normal))
    }
}

/** A button without a border that reads like a link. It serves the small actions inside the form. */
@Composable
private fun LinkButton(text: String, tag: String, onClick: () -> Unit) {
    Text(
        text,
        color = KarpfenTheme.colors.accent,
        style = KarpfenTheme.typography.uiSmall,
        maxLines = 1,
        modifier = Modifier
            .clickable(remember { MutableInteractionSource() }, LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .testTag(tag),
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
        textStyle = KarpfenTheme.typography.uiSmall.copy(color = colors.text),
        cursorBrush = SolidColor(colors.text),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.height(CONTROL_HEIGHT.dp).testTag(tag),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.window, RoundedCornerShape(CORNER.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(CORNER.dp))
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) Text(hint, color = colors.textMuted, style = KarpfenTheme.typography.uiSmall, maxLines = 1)
                inner()
            }
        },
    )
}

/**
 * A [Field] with the button Pick, which opens the list of [options] to pick from. A pick replaces the text of the
 * field. The field takes any other text too, because a bridge may name something the project does not have. Without
 * options there is no button.
 */
@Composable
private fun PickField(value: String, onChange: (String) -> Unit, hint: String, options: List<String>, modifier: Modifier, tag: String) {
    var open by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Field(value, onChange, hint, Modifier.weight(1f), tag)
        if (options.isEmpty()) return@Row
        Box {
            LinkButton("Pick", BridgeFormTestTags.pick(tag)) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, style = KarpfenTheme.typography.uiSmall) },
                        onClick = {
                            open = false
                            onChange(option)
                        },
                        modifier = Modifier.height(OPTION_HEIGHT.dp).testTag(BridgeFormTestTags.option(tag, option)),
                    )
                }
            }
        }
    }
}

/** These settings with [key] set to [value], or without it if [value] is blank. */
private fun BridgeDraft.withSetting(key: String, value: String): BridgeDraft =
    copy(settings = if (value.isBlank()) settings - key else settings + (key to value))

/** This list with the element at [index] replaced by what [transform] makes of it. */
private fun <T> List<T>.replaced(index: Int, transform: (T) -> T): List<T> = mapIndexed { at, item -> if (at == index) transform(item) else item }

/** This list without the element at [index]. */
private fun <T> List<T>.without(index: Int): List<T> = filterIndexed { at, _ -> at != index }

/** What the empty field of the address of an inbound route shows for a bridge of [kind]. */
private fun fromHint(kind: BridgeKind): String = when (kind) {
    BridgeKind.HTTP -> "/path"
    BridgeKind.MQTT -> "topic/filter"
    BridgeKind.ROS -> "/topic"
}

/** What the empty field of the destination of an outbound route shows for a bridge of [kind]. */
private fun toHint(kind: BridgeKind): String = when (kind) {
    BridgeKind.HTTP -> "http://host:port/path"
    BridgeKind.MQTT -> "topic"
    BridgeKind.ROS -> "/topic"
}

/** What the chip of this choice says. */
private val Follows.title: String
    get() = when (this) {
        Follows.PROPERTY -> "A property"
        Follows.STATE -> "Active states"
        Follows.EVENT -> "An event"
    }

/** The label of the field of the followed thing for this choice. */
private val Follows.label: String
    get() = when (this) {
        Follows.PROPERTY -> "Property"
        Follows.STATE -> "State machine"
        Follows.EVENT -> "Event"
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
private const val FORM_WIDTH = 820

/** The share of the window the form takes at most, in each direction. */
private const val FORM_SHARE = 0.9f

/** The greatest height of the list of problems in dp. A longer list scrolls. */
private const val PROBLEMS_HEIGHT = 80

/** The width of the label column in dp. Every row of the form starts with a label of this width. */
private const val LABEL_WIDTH = 112

/** The width of the label of the second field of a row in dp. */
private const val SECOND_LABEL_WIDTH = 40

/** The height of a field, a choice and a button in dp. */
private const val CONTROL_HEIGHT = 22

/** The least height of a row in dp. */
private const val ROW_HEIGHT = 22

/** The distance between two rows in dp. */
private const val ROW_GAP = 4

/** The room above the heading of a part of the form in dp. */
private const val SECTION_GAP = 6

/** The distance between two controls of a row in dp. */
private const val GAP = 8

/** The radius of the corners of the form and its controls in dp. */
private const val CORNER = 3

/** The side of a box to tick in dp. */
private const val TICK_SIZE = 14

/** The least width of a button in dp. */
private const val BUTTON_WIDTH = 72

/** The height of an entry of a pick list in dp. */
private const val OPTION_HEIGHT = 26

/** The width of the number field of a flow policy in dp. */
private const val NUMBER_WIDTH = 64
