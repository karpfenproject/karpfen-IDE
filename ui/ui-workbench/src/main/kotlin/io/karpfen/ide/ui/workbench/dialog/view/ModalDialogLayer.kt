package io.karpfen.ide.ui.workbench.dialog.view

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse

/**
 * Lays the pending dialog ([WorkbenchDialogs]) over [content] and makes it modal for the keyboard as it is for the
 * pointer: while [request] is shown, the focus cannot enter [content] — neither by Tab nor by a focus request — so no key
 * reaches the workbench behind the dialog (the dialog itself cycles Tab through its controls); when it is answered, the
 * element of [content] that had the focus before the dialog took it gets it back (the editor after Cmd+W → Cancel).
 */
@Composable
internal fun ModalDialogLayer(
    request: DialogRequest<*>?,
    onRespond: (DialogResponse) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val covered = remember { CoveredFocus() }
    val modal = request != null
    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(covered.requester)
                .focusProperties { if (modal) onEnter = { cancelFocusChange() } }
                .focusGroup(),
        ) { content() }
        WorkbenchDialogs(request, onRespond)
    }
    // Applied before the dialog's effect requests its focus, so the element that has the focus now is remembered.
    DisposableEffect(modal) {
        if (modal) covered.save()
        onDispose { }
    }
    LaunchedEffect(modal) {
        if (!modal) covered.restore()
    }
}

/** The focus of the content a dialog covers: which of its elements had the focus when the dialog took it. */
private class CoveredFocus {
    /** Attached to the covered content's focus group. */
    val requester = FocusRequester()
    private var saved = false

    /** Remembers the focused element of the content (nothing if the focus is elsewhere). */
    fun save() {
        saved = requester.saveFocusedChild()
    }

    /** Gives the focus back to the remembered element, if there is one and it still exists. */
    fun restore() {
        if (saved) requester.restoreFocusedChild()
        saved = false
    }
}
