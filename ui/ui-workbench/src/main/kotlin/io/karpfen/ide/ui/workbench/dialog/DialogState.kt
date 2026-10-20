package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/** A dialog the workbench needs answered (spec §8). */
public sealed interface DialogRequest {
    /** Unsaved changes in [paths] before closing a tab, the folder or the application. Answers: SAVE, DISCARD, CANCEL. */
    public data class UnsavedChanges(public val paths: List<Path>) : DialogRequest

    /** Confirm deleting [path] ([toTrash]: it goes to the trash). Answers: CONFIRM, CANCEL. */
    public data class ConfirmDelete(public val path: Path, public val toTrash: Boolean) : DialogRequest

    /** Ask for a new name of [path]. Answers: [DialogResponse.Text] or CANCEL. */
    public data class Rename(public val path: Path) : DialogRequest

    /** Ask for the name of a new [kind] file in [directory]. Answers: [DialogResponse.Text] or CANCEL. */
    public data class NewFile(public val directory: Path, public val kind: FileTemplateKind) : DialogRequest

    /** Show an error. Answer: CONFIRM. */
    public data class Error(public val title: String, public val message: String) : DialogRequest
}

/** The answer to a [DialogRequest]. */
public sealed interface DialogResponse {
    /** Save the changes. */
    public data object Save : DialogResponse

    /** Discard the changes. */
    public data object Discard : DialogResponse

    /** Confirm (delete, acknowledge an error). */
    public data object Confirm : DialogResponse

    /** Cancel the operation. */
    public data object Cancel : DialogResponse

    /** Entered text (rename, new file name). */
    public data class Text(public val value: String) : DialogResponse
}

/**
 * The pending dialog (spec §8). The mediator suspends in [ask] until the composable answers through [respond]; one
 * dialog at a time (further requests wait).
 */
public class DialogState {

    /** The dialog to show, or null. */
    public val current: StateFlow<DialogRequest?> get() = TODO("WP-WORKBENCH: current dialog")

    /** Shows [request] and suspends until it is answered (cancellation of the caller cancels the dialog). */
    public suspend fun ask(request: DialogRequest): DialogResponse = TODO("WP-WORKBENCH: ask")

    /** Answers the current dialog; no-op without one. */
    public fun respond(response: DialogResponse): Unit = TODO("WP-WORKBENCH: respond")
}
