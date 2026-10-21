package io.karpfen.ide.ui.editor.popup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import io.karpfen.ide.ui.editor.overlay.EditorOverlayHost

/*
 * The slots of [EditorPopups]: each reads what its popup shows from the host (the `EditorPopupModel`'s flows and the
 * host's geometry), emits nothing while its popup is closed, and wires the stateless popup composable to the intents.
 * Positions are in the host's coordinate space (the text area), as the overlay contract defines.
 */

/**
 * The find/replace bar while `overlays.findBar` is open, rendering the model's query and matches. Its field state lives
 * as long as the editor's composition (the replacement survives closing the bar); every change of
 * `overlays.findFocusRequests` focuses the query field and selects the query ([FindBarFields.takeFocusRequest]).
 */
@Composable
internal fun FindBarSlot(host: EditorOverlayHost, intents: PopupIntents) {
    val model = host.state
    val mode by model.overlays.findBar.collectAsState()
    val query by model.find.query.collectAsState()
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
        state = result,
        fields = fields,
        queryFocus = queryFocus,
        theme = host.theme,
        textStyle = host.textStyle,
        onQueryTextChange = intents::findTextChanged,
        onToggle = intents::toggleFindOption,
        onNext = intents::findNext,
        onPrevious = intents::findPrevious,
        onReplace = intents::replace,
        onReplaceAll = intents::replaceAll,
        onClose = intents::closeFind,
    )
}

/** The completion list while a session is open and the caret is on screen (it is anchored at the caret). */
@Composable
internal fun CompletionSlot(host: EditorOverlayHost, intents: PopupIntents) {
    val state by host.state.completion.state.collectAsState()
    val open = state ?: return
    val anchor = host.caretBounds ?: return
    CompletionPopup(open, anchor, host.theme, host.textStyle, intents::selectCompletion, intents::acceptCompletion)
}

/** The hover tooltip while `overlays.hover` is set and the start of its range is on screen (the anchor). */
@Composable
internal fun HoverSlot(host: EditorOverlayHost, intents: PopupIntents) {
    val content by host.state.overlays.hover.collectAsState()
    val shown = content ?: return
    val anchor = host.boundsOf(shown.range.start) ?: return
    HoverTooltip(shown, anchor, host.theme, host.textStyle, intents::dismissHover)
}

/** The go-to-line popup while `overlays.goToLine` is open, with the caret's line and the line count as hint. */
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
 * The context menu while `overlays.contextMenu` is open. Enablement is `EditorPopupModel.isEnabled`, re-evaluated
 * whenever `enabledActions` changes (the menu recomposes with a new predicate).
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
