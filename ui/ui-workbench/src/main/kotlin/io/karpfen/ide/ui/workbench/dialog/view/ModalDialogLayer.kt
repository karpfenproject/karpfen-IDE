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
 * Lays the pending dialog of [WorkbenchDialogs] over [content] and makes it modal for the keyboard, just as it is for
 * the pointer. While [request] is shown, the focus cannot enter [content], neither by Tab nor by a focus request. No
 * key therefore reaches the workbench behind the dialog. The dialog itself cycles Tab through its own controls. Once
 * the dialog is answered, the element of [content] that had the focus before the dialog took it gets the focus back.
 * For example, the editor gets it back after the user presses Cmd+W and then cancels the unsaved-changes dialog.
 *
 * The covered focus is saved in a [DisposableEffect]. Compose applies it before the dialog's own effect requests the
 * focus for the dialog, so it still sees the element that had the focus before the dialog appeared.
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
    DisposableEffect(modal) {
        if (modal) covered.save()
        onDispose { }
    }
    LaunchedEffect(modal) {
        if (!modal) covered.restore()
    }
}

/** The focus of the content a dialog covers. It remembers which element had the focus when the dialog took it. */
private class CoveredFocus {
    /** The requester attached to the focus group of the covered content. */
    val requester = FocusRequester()
    private var saved = false

    /** Remembers the focused element of the content. Remembers nothing if the focus is outside the content. */
    fun save() {
        saved = requester.saveFocusedChild()
    }

    /** Gives the focus back to the remembered element, if there is one and it still exists. */
    fun restore() {
        if (saved) requester.restoreFocusedChild()
        saved = false
    }
}
