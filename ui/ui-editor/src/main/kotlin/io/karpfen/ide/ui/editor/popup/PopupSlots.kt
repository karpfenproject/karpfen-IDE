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
package io.karpfen.ide.ui.editor.popup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import io.karpfen.ide.ui.editor.overlay.EditorOverlayHost

/**
 * Shows the find and replace bar while `overlays.findBar` is open. The bar renders the query, the replacement and the
 * matches of the model. Only the selection and composition of the fields live in the editor's composition, in
 * [FindBarFields]. Every change of `overlays.findFocusRequests` focuses the query field and selects the query through
 * [FindBarFields.takeFocusRequest].
 */
@Composable
internal fun FindBarSlot(host: EditorOverlayHost, intents: PopupIntents) {
    val model = host.state
    val mode by model.overlays.findBar.collectAsState()
    val query by model.find.query.collectAsState()
    val replacement by model.find.replacement.collectAsState()
    val result by model.find.state.collectAsState()
    val focusRequests by model.overlays.findFocusRequests.collectAsState()
    val fields = remember(model) { FindBarFields(model.overlays.findFocusRequests.value) }
    val open = mode ?: return
    val queryFocus = remember { FocusRequester() }
    LaunchedEffect(fields, focusRequests) {
        if (fields.takeFocusRequest(focusRequests, model.find.query.value.text)) queryFocus.requestFocus()
    }
    FindReplaceBar(
        mode = open,
        query = query,
        replacement = replacement,
        state = result,
        fields = fields,
        queryFocus = queryFocus,
        theme = host.theme,
        textStyle = host.textStyle,
        onQueryTextChange = intents::findTextChanged,
        onReplacementChange = intents::replacementChanged,
        onToggle = intents::toggleFindOption,
        onNext = intents::findNext,
        onPrevious = intents::findPrevious,
        onReplace = intents::replace,
        onReplaceAll = intents::replaceAll,
        onClose = intents::closeFind,
    )
}

/**
 * Shows the completion list while a session is open, anchored at the caret. While the caret is out of view, the list
 * cannot be shown. There are two cases. A caret that is about to be revealed only delays the list, because the
 * keystroke or request that opened the list scrolls to it. A caret the user scrolled away
 * ([EditorOverlayHost.isCaretScrolledAway]) closes the session through [PopupIntents.completionHidden]. So a list
 * nobody sees never takes keys such as Enter, Tab and the arrows from the text area.
 */
@Composable
internal fun CompletionSlot(host: EditorOverlayHost, intents: PopupIntents) {
    val state by host.state.completion.state.collectAsState()
    val open = state ?: return
    val anchor = host.caretBounds
    if (anchor == null) {
        if (host.isCaretScrolledAway) LaunchedEffect(open) { intents.completionHidden() }
        return
    }
    CompletionPopup(open, anchor, host.theme, host.textStyle, intents::selectCompletion, intents::acceptCompletion)
}

/** Shows the hover tooltip while `overlays.hover` is set and the start of its range, which is the anchor, is on screen. */
@Composable
internal fun HoverSlot(host: EditorOverlayHost, intents: PopupIntents) {
    val content by host.state.overlays.hover.collectAsState()
    val shown = content ?: return
    val anchor = host.boundsOf(shown.range.start) ?: return
    HoverTooltip(shown, anchor, host.theme, host.textStyle, intents::dismissHover, intents::pointerOverTooltip)
}

/** Shows the go-to-line popup while `overlays.goToLine` is open. The hint shows the caret's line and the line count. */
@Composable
internal fun GoToLineSlot(host: EditorOverlayHost, intents: PopupIntents) {
    val model = host.state
    val open by model.overlays.goToLine.collectAsState()
    if (!open) return
    val snapshot by model.snapshot.collectAsState()
    val carets by model.caret.state.collectAsState()
    GoToLinePopup(
        currentLine = GoToLineHint.currentLine(snapshot, carets.primary.position),
        lineCount = snapshot.lineCount,
        theme = host.theme,
        textStyle = host.textStyle,
        onGo = intents::goToLine,
        onCancel = intents::cancelGoToLine,
        onDismiss = intents::dismissGoToLine,
    )
}

/**
 * Shows the context menu while `overlays.contextMenu` is open. Enablement comes from `EditorPopupModel.isEnabled`. It
 * is evaluated again whenever `enabledActions` changes, because the menu then recomposes with a new predicate.
 */
@Composable
internal fun ContextMenuSlot(host: EditorOverlayHost, items: List<EditorContextMenuItem>, intents: PopupIntents) {
    val model = host.state
    val request by model.overlays.contextMenu.collectAsState()
    val open = request ?: return
    val enabled by model.enabledActions.collectAsState()
    val isEnabled = remember(model, enabled) { { actionId: String -> model.isEnabled(actionId) } }
    EditorContextMenu(open, items, host.theme, isEnabled, intents::runContextAction, intents::dismissContextMenu)
}
