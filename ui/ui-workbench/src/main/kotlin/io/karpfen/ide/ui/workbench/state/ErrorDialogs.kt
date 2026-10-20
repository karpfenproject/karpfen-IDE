package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogState

/** Shows an error dialog with [title] and [message] and suspends until it is acknowledged. */
internal suspend fun DialogState.showError(title: String, message: String) {
    ask(DialogRequest.Error(title, message))
}
