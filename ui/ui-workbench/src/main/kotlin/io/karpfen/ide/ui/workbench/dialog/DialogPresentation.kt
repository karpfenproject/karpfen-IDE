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

/** What pressing a dialog button does: answer [Answer.response], or submit the entered text ([SubmitText]). */
internal sealed interface DialogChoice {
    /** Answers with [response]. */
    data class Answer(val response: DialogResponse) : DialogChoice

    /** Answers with the entered text (trimmed; not possible while it is blank). */
    data object SubmitText : DialogChoice
}

/** A button of a dialog; the [isDefault] one is also pressed by Enter. */
internal data class DialogButton(val label: String, val choice: DialogChoice, val isDefault: Boolean = false)

/**
 * How a [DialogRequest] is shown (presentation only, so the dialog composable just renders it): [title], [message],
 * the [buttons] — exactly the answers the request accepts — an optional text field prefilled with [input], and the
 * answer for dismissing the dialog (Escape) [dismiss].
 */
internal data class DialogPresentation(
    val title: String,
    val message: String,
    val buttons: List<DialogButton>,
    val dismiss: DialogResponse,
    val input: String? = null,
) {
    /** The response of [choice] with [text] entered, or null if the choice is not possible now (blank text). */
    fun responseFor(choice: DialogChoice, text: String): DialogResponse? = when (choice) {
        is DialogChoice.Answer -> choice.response
        DialogChoice.SubmitText -> text.trim().takeIf { it.isNotEmpty() }?.let(DialogResponse::Text)
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
                "Save the changes to ${paths.size} files?\n" + paths.joinToString("\n") { "• ${name(it)}" }
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
                    paths.joinToString("\n") { "• ${name(it)}" } + "\nSave your versions over them?"
            },
            buttons = listOf(answer("Overwrite", DialogResponse.Confirm), answer("Cancel", DialogResponse.Cancel, isDefault = true)),
            dismiss = DialogResponse.Cancel,
        )

        private fun submit(label: String) = DialogButton(label, DialogChoice.SubmitText, isDefault = true)

        private fun answer(label: String, response: DialogResponse, isDefault: Boolean = false) =
            DialogButton(label, DialogChoice.Answer(response), isDefault)

        private fun name(path: Path): String = path.fileName?.toString() ?: path.toString()
    }
}
