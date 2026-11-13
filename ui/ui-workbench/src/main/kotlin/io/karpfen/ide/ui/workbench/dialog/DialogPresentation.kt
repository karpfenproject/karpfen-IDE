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
package io.karpfen.ide.ui.workbench.dialog

import java.nio.file.Path

/**
 * What pressing a dialog button does. [Answer] answers with a fixed response. [SubmitText] answers with the entered
 * text. [SubmitSelection] answers with the selected entry of the list. [SubmitRun] answers with the ticked boxes.
 */
internal sealed interface DialogChoice {
    /** Answers with [response]. */
    data class Answer(val response: DialogResponse) : DialogChoice

    /** Answers with the entered text, trimmed. This is not possible while the text is blank. */
    data object SubmitText : DialogChoice

    /** Answers with the selected entry of the list. */
    data object SubmitSelection : DialogChoice

    /** Answers how to run: with the ticked boxes of the list and whether the box of the option below it is ticked. */
    data object SubmitRun : DialogChoice
}

/** A button of a dialog. Enter also presses the button whose [isDefault] is true. */
internal data class DialogButton(val label: String, val choice: DialogChoice, val isDefault: Boolean = false)

/**
 * How a [DialogRequest] is shown. It holds presentation only, so the dialog composable just renders it. A dialog has a
 * [title], a [message] and [buttons]. The buttons offer exactly the answers the request accepts. An optional text field
 * starts with the text [input]. An optional list shows [choices] and starts with the entry at [preselected] selected.
 * An optional list of boxes shows [ticks] and starts with the boxes of [preticked] ticked. An optional single box below
 * it shows the label [option] and starts ticked if [optionTicked] is true. An optional list of [findings] shows one row
 * of text per finding, which scrolls and cannot be clicked. [dismiss] is the answer given when the user dismisses the
 * dialog with Escape.
 */
internal data class DialogPresentation(
    val title: String,
    val message: String,
    val buttons: List<DialogButton>,
    val dismiss: DialogResponse,
    val input: String? = null,
    val choices: List<String>? = null,
    val preselected: Int = 0,
    val ticks: List<String>? = null,
    val preticked: Set<Int> = emptySet(),
    val option: String? = null,
    val optionTicked: Boolean = false,
    val findings: List<FindingRow>? = null,
) {
    /**
     * The response of [choice] while [text] is entered, the list entry at [selected] is selected, the boxes of
     * [ticked] are ticked and the box of the option is [optioned]. Returns null if the choice is not possible right
     * now, which is the case for a text answer while the text is blank.
     */
    fun responseFor(
        choice: DialogChoice,
        text: String,
        selected: Int = preselected,
        ticked: Set<Int> = preticked,
        optioned: Boolean = optionTicked,
    ): DialogResponse? = when (choice) {
        is DialogChoice.Answer -> choice.response
        DialogChoice.SubmitText -> text.trim().takeIf { it.isNotEmpty() }?.let(DialogResponse::Text)
        DialogChoice.SubmitSelection -> DialogResponse.Chosen(selected)
        DialogChoice.SubmitRun -> DialogResponse.RunWith(ticked, optioned)
    }

    companion object {
        /** The presentation of [request]. */
        fun of(request: DialogRequest<*>): DialogPresentation = when (request) {
            is DialogRequest.UnsavedChanges -> unsavedChanges(request.paths)
            is DialogRequest.ConfirmDelete -> DialogPresentation(
                title = "Delete",
                message = "Delete '${name(request.path)}'?" +
                    if (request.toTrash) " It will be moved to the trash." else " This cannot be undone.",
                buttons = listOf(answer("Delete", DialogResponse.Confirm, isDefault = true), answer("Cancel", DialogResponse.Cancel)),
                dismiss = DialogResponse.Cancel,
            )
            is DialogRequest.ConfirmOverwrite -> confirmOverwrite(request.paths)
            is DialogRequest.Rename -> DialogPresentation(
                title = "Rename",
                message = "New name for '${name(request.path)}':",
                buttons = listOf(submit("Rename"), answer("Cancel", DialogResponse.Cancel)),
                dismiss = DialogResponse.Cancel,
                input = name(request.path),
            )
            is DialogRequest.NewFile -> DialogPresentation(
                title = "New ${request.kind.displayName}",
                message = "Name of the new file in '${name(request.directory)}' (${request.kind.suffix} is added):",
                buttons = listOf(submit("Create"), answer("Cancel", DialogResponse.Cancel)),
                dismiss = DialogResponse.Cancel,
                input = "",
            )
            is DialogRequest.ChooseDescriptor -> DialogPresentation(
                title = "Run",
                message = "Choose the environment descriptor to run:",
                buttons = listOf(
                    DialogButton("Run", DialogChoice.SubmitSelection, isDefault = true),
                    answer("Cancel", DialogResponse.Cancel),
                ),
                dismiss = DialogResponse.Cancel,
                choices = request.descriptors.map { request.root.relativize(it).toString() },
                preselected = request.preselected,
            )
            is DialogRequest.ChooseBridgeFile -> DialogPresentation(
                title = "Configure Bridge",
                message = "Choose the bridge to configure:",
                buttons = listOf(
                    DialogButton("Configure", DialogChoice.SubmitSelection, isDefault = true),
                    answer("Cancel", DialogResponse.Cancel),
                ),
                dismiss = DialogResponse.Cancel,
                choices = request.bridges.map { request.root.relativize(it).toString() } + NEW_BRIDGE,
            )
            is DialogRequest.ConfigureRun -> DialogPresentation(
                title = "Run",
                message = if (request.bridges.isEmpty()) RUN_CHOICE_REMEMBERED else "$TICK_BRIDGES $RUN_CHOICE_REMEMBERED",
                buttons = listOf(
                    DialogButton("Run", DialogChoice.SubmitRun, isDefault = true),
                    answer("Cancel", DialogResponse.Cancel),
                ),
                dismiss = DialogResponse.Cancel,
                ticks = request.bridges.map { request.root.relativize(it).toString() },
                preticked = request.preselected,
                option = CAPTURE_RUN,
                optionTicked = request.capture,
            )
            is DialogRequest.ValidationReport -> DialogPresentation(
                title = request.title,
                message = ValidationReportTexts.summary(request.validation),
                buttons = listOf(answer("Close", DialogResponse.Confirm, isDefault = true)),
                dismiss = DialogResponse.Confirm,
                findings = ValidationReportTexts.rows(request.validation),
            )
            is DialogRequest.Error -> DialogPresentation(
                title = request.title,
                message = request.message,
                buttons = listOf(answer("OK", DialogResponse.Confirm, isDefault = true)),
                dismiss = DialogResponse.Confirm,
            )
        }

        private fun unsavedChanges(paths: List<Path>): DialogPresentation = DialogPresentation(
            title = "Unsaved Changes",
            message = if (paths.size == 1) {
                "Save the changes to '${name(paths.single())}'?"
            } else {
                "Save the changes to ${paths.size} files?\n" + paths.joinToString("\n") { "'${name(it)}'" }
            },
            buttons = listOf(
                answer("Save", DialogResponse.Save, isDefault = true),
                answer("Don't Save", DialogResponse.Discard),
                answer("Cancel", DialogResponse.Cancel),
            ),
            dismiss = DialogResponse.Cancel,
        )

        private fun confirmOverwrite(paths: List<Path>): DialogPresentation = DialogPresentation(
            title = "Changed on Disk",
            message = if (paths.size == 1) {
                "'${name(paths.single())}' was changed or deleted on disk while you edited it. Save your version over it?"
            } else {
                "${paths.size} files were changed or deleted on disk while you edited them:\n" +
                    paths.joinToString("\n") { "'${name(it)}'" } + "\nSave your versions over them?"
            },
            buttons = listOf(answer("Overwrite", DialogResponse.Confirm), answer("Cancel", DialogResponse.Cancel, isDefault = true)),
            dismiss = DialogResponse.Cancel,
        )

        /** The last entry of the list that picks the bridge to configure. */
        const val NEW_BRIDGE = "New bridge"

        /** The label of the box that captures a run to a file. */
        const val CAPTURE_RUN = "Capture run to file"

        /** What the run dialog asks of a workspace with bridge files. */
        private const val TICK_BRIDGES = "Tick the bridges to start with this run."

        /** What the run dialog says about its choices. */
        private const val RUN_CHOICE_REMEMBERED = "The choices are remembered for the next run."

        private fun submit(label: String) = DialogButton(label, DialogChoice.SubmitText, isDefault = true)

        private fun answer(label: String, response: DialogResponse, isDefault: Boolean = false) =
            DialogButton(label, DialogChoice.Answer(response), isDefault)

        private fun name(path: Path): String = path.fileName?.toString() ?: path.toString()
    }
}
