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
 * A dialog the workbench needs answered. The type parameter [R] is the set of answers the dialog accepts, so
 * `DialogState.ask` returns only answers its caller has to handle (e.g. no `Save` for a rename).
 */
public sealed interface DialogRequest<out R : DialogResponse> {

    /** True if [response] is one of this dialog's answers ([R]). */
    public fun accepts(response: DialogResponse): Boolean

    /** Unsaved changes in [paths] before closing a tab, the folder or the application. */
    public data class UnsavedChanges(public val paths: List<Path>) : DialogRequest<UnsavedChangesAnswer> {
        override fun accepts(response: DialogResponse): Boolean = response is UnsavedChangesAnswer
    }

    /** Confirm deleting [path] ([toTrash]: it goes to the trash). */
    public data class ConfirmDelete(public val path: Path, public val toTrash: Boolean) : DialogRequest<ConfirmAnswer> {
        override fun accepts(response: DialogResponse): Boolean = response is ConfirmAnswer
    }

    /**
     * Confirm saving [paths] over their versions on disk: each was changed or deleted on disk while it had unsaved
     * changes, and the external-change conflict is not resolved yet. Saving never silently writes over an external
     * change, so Save, Save All and Save in the unsaved-changes dialog ask this first. Confirm overwrites, Cancel keeps
     * the disk versions and the conflicts.
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
     * Choose which of the environment [descriptors] of the workspace at [root] to run; [preselected] is the index of the
     * one selected at first.
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

    /** Show an error; the only answer is [DialogResponse.Confirm] (also when the dialog is dismissed). */
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

    /** Confirm (delete, overwrite, acknowledge an error). */
    public data object Confirm : ConfirmAnswer

    /** Cancel the operation. */
    public data object Cancel : UnsavedChangesAnswer, ConfirmAnswer, TextAnswer, ChoiceAnswer

    /** Entered text (rename, new file name). */
    public data class Text(public val value: String) : TextAnswer

    /** The entry at [index] of a list was chosen. */
    public data class Chosen(public val index: Int) : ChoiceAnswer
}

/** Answers to [DialogRequest.UnsavedChanges]: Save, Discard, Cancel. */
public sealed interface UnsavedChangesAnswer : DialogResponse

/** Answers to [DialogRequest.ConfirmDelete] and [DialogRequest.ConfirmOverwrite]: Confirm, Cancel. */
public sealed interface ConfirmAnswer : DialogResponse

/** Answers to [DialogRequest.Rename] and [DialogRequest.NewFile]: Text, Cancel. */
public sealed interface TextAnswer : DialogResponse

/** Answers to [DialogRequest.ChooseDescriptor]: Chosen, Cancel. */
public sealed interface ChoiceAnswer : DialogResponse

/**
 * The pending dialog. The mediator (and the application's crash handling) suspends in [ask] until the composable
 * answers through [respond]; one dialog at a time (further requests wait).
 *
 * Requests wait in call order (a fair mutex); cancelling a waiting or a shown request removes it, so a cancelled flow
 * never leaves a dialog on screen. Used from the UI thread.
 */
public class DialogState {

    private val queue = Mutex()
    private val shown = MutableStateFlow<Pending?>(null)
    private val currentRequest = MutableStateFlow<DialogRequest<*>?>(null)

    /** The dialog to show, or null. */
    public val current: StateFlow<DialogRequest<*>?> get() = currentRequest.asStateFlow()

    /**
     * Shows [request] and suspends until it is answered (cancellation of the caller cancels the dialog). The answer is
     * cast to [R] unchecked, which is safe because [respond] accepts only the request's answers
     * ([DialogRequest.accepts]).
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
     * Answers the current dialog; no-op without one.
     * @throws IllegalArgumentException if the current dialog does not accept [response] ([DialogRequest.accepts]).
     */
    public fun respond(response: DialogResponse) {
        val pending = shown.value ?: return
        require(pending.request.accepts(response)) { "${pending.request} does not accept $response" }
        pending.answer.complete(response)
    }

    /** The shown [request] and the [answer] its caller awaits. */
    private class Pending(val request: DialogRequest<*>, val answer: CompletableDeferred<DialogResponse>)
}
