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

import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path

/**
 * A dialog the workbench needs answered. The type parameter [R] is the set of answers the dialog accepts. That way
 * `DialogState.ask` returns only answers its caller has to handle. A rename, for example, can never be answered with
 * `Save`.
 */
public sealed interface DialogRequest<out R : DialogResponse> {

    /** True if [response] is one of the answers this dialog accepts, that is, an [R]. */
    public fun accepts(response: DialogResponse): Boolean

    /** Unsaved changes in [paths] before closing a tab, the folder or the application. */
    public data class UnsavedChanges(public val paths: List<Path>) : DialogRequest<UnsavedChangesAnswer> {
        override fun accepts(response: DialogResponse): Boolean = response is UnsavedChangesAnswer
    }

    /** Confirm that [path] is to be deleted. If [toTrash] is true, it goes to the trash and is not deleted for good. */
    public data class ConfirmDelete(public val path: Path, public val toTrash: Boolean) : DialogRequest<ConfirmAnswer> {
        override fun accepts(response: DialogResponse): Boolean = response is ConfirmAnswer
    }

    /**
     * Confirm that [paths] are to be saved over their versions on disk. Each of them was changed or deleted on disk
     * while it had unsaved changes, and that conflict is not resolved yet. Saving never silently writes over an
     * external change, so Save, Save All and Save in the unsaved-changes dialog ask this first. Confirm overwrites the
     * files. Cancel keeps the versions on disk and keeps the conflicts open.
     */
    public data class ConfirmOverwrite(public val paths: List<Path>) : DialogRequest<ConfirmAnswer> {
        override fun accepts(response: DialogResponse): Boolean = response is ConfirmAnswer
    }

    /** Ask for a new name of [path]. */
    public data class Rename(public val path: Path) : DialogRequest<TextAnswer> {
        override fun accepts(response: DialogResponse): Boolean = response is TextAnswer
    }

    /** Ask for the name of a new [kind] file in [directory]. */
    public data class NewFile(public val directory: Path, public val kind: FileTemplateKind) : DialogRequest<TextAnswer> {
        override fun accepts(response: DialogResponse): Boolean = response is TextAnswer
    }

    /**
     * Choose which of the environment [descriptors] of the workspace at [root] to run. [preselected] is the index of
     * the descriptor that is selected when the dialog opens.
     */
    public data class ChooseDescriptor(
        public val root: Path,
        public val descriptors: List<Path>,
        public val preselected: Int = 0,
    ) : DialogRequest<ChoiceAnswer> {
        init {
            require(preselected in descriptors.indices) { "the preselected index $preselected is out of ${descriptors.indices}" }
        }

        override fun accepts(response: DialogResponse): Boolean =
            response == DialogResponse.Cancel || (response is DialogResponse.Chosen && response.index in descriptors.indices)
    }

    /** Show an error. The only answer is [DialogResponse.Confirm], also when the user dismisses the dialog. */
    public data class Error(public val title: String, public val message: String) : DialogRequest<DialogResponse.Confirm> {
        override fun accepts(response: DialogResponse): Boolean = response == DialogResponse.Confirm
    }
}

/** An answer to a [DialogRequest]. */
public sealed interface DialogResponse {
    /** Save the changes. */
    public data object Save : UnsavedChangesAnswer

    /** Discard the changes. */
    public data object Discard : UnsavedChangesAnswer

    /** Confirm the operation: delete, overwrite, or acknowledge an error. */
    public data object Confirm : ConfirmAnswer

    /** Cancel the operation. */
    public data object Cancel : UnsavedChangesAnswer, ConfirmAnswer, TextAnswer, ChoiceAnswer

    /** The entered text, such as the new name of a renamed file or the name of a new file. */
    public data class Text(public val value: String) : TextAnswer

    /** The entry at [index] of a list was chosen. */
    public data class Chosen(public val index: Int) : ChoiceAnswer
}

/** The answers to [DialogRequest.UnsavedChanges], which are Save, Discard and Cancel. */
public sealed interface UnsavedChangesAnswer : DialogResponse

/** The answers to [DialogRequest.ConfirmDelete] and [DialogRequest.ConfirmOverwrite], which are Confirm and Cancel. */
public sealed interface ConfirmAnswer : DialogResponse

/** The answers to [DialogRequest.Rename] and [DialogRequest.NewFile], which are Text and Cancel. */
public sealed interface TextAnswer : DialogResponse

/** The answers to [DialogRequest.ChooseDescriptor], which are Chosen and Cancel. */
public sealed interface ChoiceAnswer : DialogResponse

/**
 * Holds the pending dialog. The workbench mediator and the application's crash handling suspend in [ask] until the
 * dialog composable answers through [respond]. Only one dialog is shown at a time. Further requests wait.
 *
 * Waiting requests are served in call order, because a fair mutex guards the queue. Cancelling a request removes it,
 * whether it is waiting or already shown, so a cancelled flow never leaves a dialog on screen. Use the class from the
 * UI thread only.
 */
public class DialogState {

    private val queue = Mutex()
    private val shown = MutableStateFlow<Pending?>(null)
    private val currentRequest = MutableStateFlow<DialogRequest<*>?>(null)

    /** The dialog to show, or null. */
    public val current: StateFlow<DialogRequest<*>?> get() = currentRequest.asStateFlow()

    /**
     * Shows [request] and suspends until it is answered. Cancelling the caller cancels the dialog. The answer is cast
     * to [R] without a check. That is safe, because [respond] only accepts answers for which [DialogRequest.accepts]
     * returns true.
     */
    public suspend fun <R : DialogResponse> ask(request: DialogRequest<R>): R = queue.withLock {
        val pending = Pending(request, CompletableDeferred())
        shown.value = pending
        currentRequest.value = request
        try {
            @Suppress("UNCHECKED_CAST")
            pending.answer.await() as R
        } finally {
            shown.value = null
            currentRequest.value = null
        }
    }

    /**
     * Answers the current dialog. Does nothing if no dialog is shown.
     *
     * @throws IllegalArgumentException if the current dialog does not accept [response], as told by
     *   [DialogRequest.accepts].
     */
    public fun respond(response: DialogResponse) {
        val pending = shown.value ?: return
        require(pending.request.accepts(response)) { "${pending.request} does not accept $response" }
        pending.answer.complete(response)
    }

    /** The shown [request] and the [answer] its caller awaits. */
    private class Pending(val request: DialogRequest<*>, val answer: CompletableDeferred<DialogResponse>)
}
