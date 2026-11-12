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
package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.ui.editor.model.action.AutoClosedPairs
import io.karpfen.ide.ui.editor.model.action.ClipboardPort
import io.karpfen.ide.ui.editor.model.action.EditorAction
import io.karpfen.ide.ui.editor.model.action.EditorActionRegistry
import io.karpfen.ide.ui.editor.model.action.EditorKeymap
import io.karpfen.ide.ui.editor.model.action.typeTextAction
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import io.karpfen.ide.ui.editor.model.completion.CompletionSessionModel
import io.karpfen.ide.ui.editor.model.find.FindModel
import io.karpfen.ide.ui.editor.model.navigation.GoToLineQuery
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The default [CodeEditorState]. It is only a coordinator and should not grow into a god class of more than about 300
 * lines. It creates the models and delegates to internal collaborators, each with one responsibility.
 *
 * The models are the `CaretModel` with the tab width of the settings, the `AutoClosedPairs`, the `FindModel` with
 * [backgroundDispatcher], and the `CompletionSessionModel`. The find and completion models use [ActionRunner] as their
 * executor. These models are created here and not injected. They are bound to this editor's text, caret and executor.
 * They do not need Compose and are deterministic, because time and threads come from the injected scope and background
 * dispatcher. So the editor's tests use the real models.
 *
 * [EditorActionContextAdapter] is the `EditorActionContext` of the actions and reads tokens lazily through
 * `session.tokensFor`. Its `EditorUiPort` is [EditorUiRequests], which handles find bar seeding, find next and find
 * previous through the `FindModel`, go to line, completion and go to definition.
 *
 * [ActionRunner] executes every action. After each run it triggers the [CaretReveal] and refreshes the
 * [ActionEnablement].
 *
 * [EditorCompletionSource] is the suspending completion source on [backgroundDispatcher]. [HighlightPipeline] and
 * [BracketTracker] run on [backgroundDispatcher] too. The highlight pipeline includes the semantic carry-over described
 * on [EditorViewModel.highlights].
 *
 * [SymbolLookup] waits a bounded time for a current parse and looks up hover content and definitions. It feeds
 * [HoverController], which runs the [CodeEditorState.HOVER_DELAY] and the hide grace in the editor's scope. It also
 * feeds [DefinitionNavigator], which emits [navigationRequests].
 *
 * [PointerGestures] holds the click and drag policy. [ViewRequests] holds the pending scroll and focus requests.
 *
 * All work runs in a child scope of [parentScope]. Its dispatcher is the UI thread, which is the only writer of the
 * text. [close] cancels the scope.
 */
internal class DefaultCodeEditorState(
    override val session: DocumentSession,
    languageServices: LanguageServices,
    parentScope: CoroutineScope,
    clipboard: ClipboardPort,
    override val settings: StateFlow<EditorSettings>,
    private val keymap: EditorKeymap,
    private val actions: EditorActionRegistry,
    backgroundDispatcher: CoroutineDispatcher,
) : CodeEditorState {

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val text = session.text
    private val requests = ViewRequests()
    private val focus = MutableStateFlow(false)
    private var closed = false

    override val overlays: EditorOverlayState = EditorOverlayState()
    override val viewport: EditorViewport = EditorViewport()
    override val caret: CaretModel = CaretModel(text, scope) { settings.value.tabDisplayWidth }

    private val reveal = CaretReveal(text, caret, viewport, requests)
    private val runner = ActionRunner(
        text = text,
        caret = caret,
        context = { context },
        onCaretsOrTextChanged = reveal::revealPrimary,
        afterRun = { enablement.refresh() },
    )

    override val find: FindModel = FindModel(text, caret, runner, scope, backgroundDispatcher)
    override val completion: CompletionSessionModel =
        CompletionSessionModel(text, caret, EditorCompletionSource(session, languageServices, backgroundDispatcher), runner, scope)

    private val lookup = SymbolLookup(session, languageServices, backgroundDispatcher)
    private val navigator = DefinitionNavigator(scope, lookup::definitions)
    private val context = EditorActionContextAdapter(
        session = session,
        caret = caret,
        autoPairs = AutoClosedPairs(text),
        viewport = viewport,
        clipboard = clipboard,
        ui = EditorUiRequests(text, caret, find, completion, overlays, reveal::revealPrimary, navigator::goToDefinition),
    )
    private val enablement = ActionEnablement(actions, context, text, caret, scope)
    private val hover =
        HoverController(scope, overlays, text.snapshot, CodeEditorState.HOVER_DELAY, HoverController.HIDE_GRACE, lookup::hoverAt)
    private val gestures = PointerGestures(text, caret, keymap.platform, navigator::goToDefinition)
    private val highlightPipeline = HighlightPipeline(session, languageServices, scope, backgroundDispatcher)
    private val brackets = BracketTracker(session, caret, languageServices, scope, backgroundDispatcher)

    override val highlights: StateFlow<HighlightSnapshot> get() = highlightPipeline.highlights
    override val bracketMatch: StateFlow<BracketMatchSnapshot> get() = brackets.bracketMatch
    override val enabledActions: StateFlow<Set<String>> get() = enablement.enabledActions
    override val isFocused: StateFlow<Boolean> = focus.asStateFlow()
    override val scrollRequest: StateFlow<ScrollRequest?> get() = requests.scrollRequest
    override val focusRequested: StateFlow<Boolean> get() = requests.focusRequested
    override val navigationRequests: SharedFlow<Location> get() = navigator.navigationRequests

    override fun execute(actionId: String): Boolean {
        val action = actions.get(actionId) ?: return false
        return execute(action)
    }

    override fun execute(action: EditorAction): Boolean = runner.execute(action)

    override fun type(text: String) {
        if (text.isEmpty()) return
        hover.hide()
        if (execute(typeTextAction(text))) completion.onTyped(text)
    }

    override fun handleKey(stroke: KeyStroke): Boolean {
        hover.hide()
        if (completion.handleKey(stroke)) {
            enablement.refresh()
            return true
        }
        val actionId = keymap.actionFor(stroke) ?: return false
        return execute(actionId)
    }

    override fun pointerPressed(offset: Int, clickCount: Int, modifiers: Set<KeyModifier>) {
        completion.close()
        overlays.closeContextMenu()
        hover.hide()
        requests.requestFocus()
        gestures.pressed(offset, clickCount, modifiers)
        enablement.refresh()
    }

    override fun pointerDragged(offset: Int) {
        gestures.dragged(offset)
        enablement.refresh()
    }

    override fun pointerReleased() {
        gestures.released()
    }

    override fun pointerHover(offset: Int?) {
        hover.pointerAt(offset)
    }

    override fun pointerOverTooltip(over: Boolean) {
        hover.pointerOnTooltip(over)
    }

    override fun openContextMenuAt(offset: Int, x: Float, y: Float) {
        completion.close()
        val primary = caret.primary
        val insideSelection = primary.hasSelection && offset in primary.selection.start..primary.selection.end
        if (!insideSelection) caret.moveTo(offset)
        requests.requestFocus()
        overlays.openContextMenu(ContextMenuRequest(offset, x, y))
        enablement.refresh()
    }

    override fun onFocusChanged(focused: Boolean) {
        focus.value = focused
        if (!focused) {
            completion.close()
            hover.hide()
        }
    }

    override fun scrollTo(offset: Int, center: Boolean) {
        requests.scrollTo(ScrollRequest(offset.coerceIn(0, text.snapshot.value.length), center))
    }

    override fun consumeScrollRequest(request: ScrollRequest) {
        requests.consumeScroll(request)
    }

    override fun consumeFocusRequest() {
        requests.consumeFocus()
    }

    override fun goToDefinition(offset: Int) {
        navigator.goToDefinition(offset)
    }

    override fun goToLine(query: GoToLineQuery) {
        val offset = query.offsetIn(text.snapshot.value)
        caret.moveTo(offset)
        scrollTo(offset, center = true)
        overlays.closeGoToLine()
        requestFocus()
        enablement.refresh()
    }

    override fun requestFocus() {
        requests.requestFocus()
    }

    override fun close() {
        if (closed) return
        closed = true
        completion.close()
        hover.hide()
        scope.cancel()
        overlays.closeAll()
    }
}
