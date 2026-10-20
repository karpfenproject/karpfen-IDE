package io.karpfen.ide.ui.workbench.dialog

import androidx.compose.runtime.Composable
import io.karpfen.ide.ui.workbench.state.DialogRequest
import io.karpfen.ide.ui.workbench.state.DialogResponse

/** Renders the pending [request] as a modal dialog and reports the answer through [onRespond]. */
@Composable
internal fun WorkbenchDialogs(request: DialogRequest?, onRespond: (DialogResponse) -> Unit): Unit =
    TODO("WP-WORKBENCH: dialogs")
