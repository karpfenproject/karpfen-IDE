package io.karpfen.ide.ui.workbench.dialog

import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * A dialog the workbench needs answered (spec §8). The type parameter [R] is the set of answers the dialog accepts, so
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

    /** Ask for a new name of [path]. */
    public data class Rename(public val path: Path) : DialogRequest<TextAnswer> {
        override fun accepts(response: DialogResponse): Boolean = response is TextAnswer
    }

    /** Ask for the name of a new [kind] file in [directory]. */
    public data class NewFile(public val directory: Path, public val kind: FileTemplateKind) : DialogRequest<TextAnswer> {
        override fun accepts(response: DialogResponse): Boolean = response is TextAnswer
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

    /** Confirm (delete, acknowledge an error). */
    public data object Confirm : ConfirmAnswer

    /** Cancel the operation. */
    public data object Cancel : UnsavedChangesAnswer, ConfirmAnswer, TextAnswer

    /** Entered text (rename, new file name). */
    public data class Text(public val value: String) : TextAnswer
}

/** Answers to [DialogRequest.UnsavedChanges]: Save, Discard, Cancel. */
public sealed interface UnsavedChangesAnswer : DialogResponse

/** Answers to [DialogRequest.ConfirmDelete]: Confirm, Cancel. */
public sealed interface ConfirmAnswer : DialogResponse

/** Answers to [DialogRequest.Rename] and [DialogRequest.NewFile]: Text, Cancel. */
public sealed interface TextAnswer : DialogResponse

/**
 * The pending dialog (spec §8). The mediator (and WP-APP's crash handling) suspends in [ask] until the composable
 * answers through [respond]; one dialog at a time (further requests wait).
 */
public class DialogState {

    /** The dialog to show, or null. */
    public val current: StateFlow<DialogRequest<*>?> get() = TODO("WP-WORKBENCH: current dialog")

    /** Shows [request] and suspends until it is answered (cancellation of the caller cancels the dialog). */
    public suspend fun <R : DialogResponse> ask(request: DialogRequest<R>): R = TODO("WP-WORKBENCH: ask")

    /**
     * Answers the current dialog; no-op without one.
     * @throws IllegalArgumentException if the current dialog does not accept [response] ([DialogRequest.accepts]).
     */
    public fun respond(response: DialogResponse): Unit = TODO("WP-WORKBENCH: respond")
}
