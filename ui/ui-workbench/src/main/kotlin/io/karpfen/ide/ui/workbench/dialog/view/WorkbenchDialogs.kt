package io.karpfen.ide.ui.workbench.dialog.view

import androidx.compose.runtime.Composable
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse

/**
 * Renders the pending [request] as a modal dialog and reports the answer through [onRespond]; it offers exactly the
 * answers the request accepts (`DialogRequest.accepts`).
 */
@Composable
internal fun WorkbenchDialogs(request: DialogRequest<*>?, onRespond: (DialogResponse) -> Unit): Unit =
    TODO("WP-WORKBENCH: dialogs")
