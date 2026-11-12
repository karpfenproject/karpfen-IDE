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
import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.HighlightSpan
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.action.ClipboardPort
import io.karpfen.ide.ui.editor.model.action.EditorAction
import io.karpfen.ide.ui.editor.model.action.EditorActionRegistry
import io.karpfen.ide.ui.editor.model.action.EditorKeymap
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The display settings of an editor.
 *
 * @property fontSize the font size in sp.
 * @property tabDisplayWidth columns per tab stop when rendering tab characters.
 * @property showLineNumbers whether the gutter shows one-based line numbers.
 * @property readOnly true for a document the user cannot change. The editor then draws its text greyed out, so it
 *   reads as something to look at and not to type into. Refusing the changes is the business of the document's text.
 */
public data class EditorSettings(
    public val fontSize: Float = 13f,
    public val tabDisplayWidth: Int = 4,
    public val showLineNumbers: Boolean = true,
    public val readOnly: Boolean = false,
) {
    init {
        require(fontSize > 0f) { "fontSize must be positive, was $fontSize" }
        require(tabDisplayWidth >= 1) { "tabDisplayWidth must be >= 1, was $tabDisplayWidth" }
    }
}

/**
 * The highlight spans for one text [version]. The spans use offsets of that version. The view maps older spans forward
 * with `EditableText.mapRange`. This is an immutable result with a version, and such results of the UI modules have
 * names that end in `Snapshot`.
 */
public data class HighlightSnapshot(public val version: Long, public val spans: List<HighlightSpan>)

/**
 * The bracket pair at the primary caret, computed for text [version]. [match] uses offsets of that version and is null
 * if there is no pair. The view draws it only while [version] is the current one, or maps it forward like highlights.
 */
public data class BracketMatchSnapshot(public val version: Long, public val match: BracketMatch?)

/** A request to scroll so that [offset] is visible, centred if [center] is true. The view carries it out. */
public data class ScrollRequest(public val offset: Int, public val center: Boolean)

/**
 * The part of an editor that the workbench may use. This interface follows interface segregation. It offers the
 * document, the carets, focus and enablement for command forwarding, the navigation requests and the navigation
 * intents. The sub-states of the workbench, such as `OutlineState` and `StatusBarState`, and the collaborators of the
 * mediator depend on this role. So their tests fake only this interface. Use it from the UI thread.
 */
public interface EditorHandle : AutoCloseable {

    /** The edited document. */
    public val session: DocumentSession

    /** The carets and the selection. The status bar position, the outline highlight and navigation read them. */
    public val caret: CaretModel

    /** The display settings. The display column in the status bar uses `tabDisplayWidth`. */
    public val settings: StateFlow<EditorSettings>

    /** The ids of the registered actions that are enabled now, as described on [EditorPopupModel.enabledActions]. */
    public val enabledActions: StateFlow<Set<String>>

    /**
     * True while the text area of the editor has keyboard focus. The view reports focus through
     * [EditorViewModel.onFocusChanged]. The workbench forwards text editing commands from the menu only while the
     * active editor is focused. `WorkbenchState.registerCommands` implements that rule.
     */
    public val isFocused: StateFlow<Boolean>

    /**
     * The navigation targets of go to definition and of primary+click. Their ranges carry a version
     * ([Location.version], [Location.lineage]). This is an event stream without replay. Its owner collects it for the
     * whole life of the editor. In the workbench, the mediator collects it from the creation of the tab's editor until
     * the tab closes, so a lookup that finishes after a tab switch is not lost. The `onNavigate` parameter of
     * `CodeEditor` collects it only in simple embeddings without a workbench.
     */
    public val navigationRequests: SharedFlow<Location>

    /**
     * Runs the registered action [actionId] if it is enabled. Afterwards it reveals the primary caret if the carets or
     * the text changed. Returns whether the action did something.
     */
    public fun execute(actionId: String): Boolean

    /** Scrolls so that [offset] is visible, centred if [center] is true. It does so by setting a pending scroll request. */
    public fun scrollTo(offset: Int, center: Boolean = false)

    /**
     * Asks the view to focus the text area. The request stays pending until the view consumes it, so it also works
     * before the editor is composed.
     */
    public fun requestFocus()

    /** Cancels the background work, such as highlighting, find and completion. Calling it again does nothing. */
    override fun close()
}

/**
 * What `CodeEditor` needs beyond the model of the popups. This interface follows interface segregation. It offers the
 * geometry, the render results, the pending view requests and the input intents, none of which need Compose. The view
 * converts raw input into these intents and holds no input policy of its own, because composables contain no business
 * logic. So the input rules are unit-tested without Compose.
 */
public interface EditorViewModel {

    /** The display settings. */
    public val settings: StateFlow<EditorSettings>

    /** The scroll position and geometry. */
    public val viewport: EditorViewport

    /**
     * The highlight spans, computed on the background dispatcher from the tokens and the parse of the session.
     *
     * When the latest parse has the version of the tokens, the spans come from `LanguageServices.highlight`, which
     * overlays the lexical styles with the semantic ones. The parse lags behind the tokens on every keystroke until the
     * debounced parse arrives. Then the fresh lexical spans are overlaid with the semantic spans
     * (`HighlightStyle.isSemantic`) of the previous snapshot, using `overlayHighlights` of core-lang. Those spans are
     * mapped to the tokens' version with `EditableText.mapRange`, and spans that do not map or become empty are dropped.
     * So the semantic colours of untouched names do not flicker while the user types.
     */
    public val highlights: StateFlow<HighlightSnapshot>

    /** The bracket pair at the primary caret, computed on the background dispatcher. */
    public val bracketMatch: StateFlow<BracketMatchSnapshot>

    /**
     * The latest scroll request that the view has not consumed yet, or null. Requests come from [EditorHandle.scrollTo]
     * and from the caret reveal. A newer request replaces an older pending one.
     */
    public val scrollRequest: StateFlow<ScrollRequest?>

    /** Marks [request] as handled. It clears [scrollRequest] if [request] is still the pending one. */
    public fun consumeScrollRequest(request: ScrollRequest)

    /**
     * True while a focus request from [EditorHandle.requestFocus] is pending. The view focuses the text area and
     * consumes the request.
     */
    public val focusRequested: StateFlow<Boolean>

    /** Marks the pending focus request as handled. */
    public fun consumeFocusRequest()

    /**
     * Reports that the text area gained or lost keyboard focus, and updates [EditorHandle.isFocused]. On focus loss the
     * state closes completion and hides the hover tooltip. The context menu and the find bar are popups of their own
     * and stay open.
     */
    public fun onFocusChanged(focused: Boolean)

    /**
     * Types [text] at the caret and reveals the caret. This is the text input path. It runs the typing action first and
     * then checks the completion auto-triggers.
     */
    public fun type(text: String)

    /**
     * Handles a key stroke of the focused text area. Completion gets the stroke first through
     * `completion.handleKey`. Then the action of the keymap runs, if it is registered and enabled. Returns true exactly
     * when the stroke was handled, which means completion consumed it or a bound action was enabled and executed. The
     * view consumes exactly those strokes.
     *
     * So the focused component handles its strokes first, and an accelerator of the window menu only sees a stroke that
     * nobody consumed. Swing key bindings skip consumed events, and the macOS screen menu bar delivers its key
     * equivalents as key events first. As a result, one key press runs exactly one action.
     */
    public fun handleKey(stroke: KeyStroke): Boolean

    /**
     * Handles a press of the primary button at text [offset]. The view hit-tests the offset in the current text.
     * [clickCount] is 1, 2 or 3, and higher counts count as 3. [modifiers] are the physical modifiers held.
     *
     * The state decides what happens. This is input policy and is unit-tested on virtual time. A single click with the
     * primary modifier goes to the definition at [offset] and leaves the caret alone. The primary modifier is
     * `HostPlatform.primaryModifier` of the platform of the editor's keymap. Otherwise Shift extends the selection to
     * [offset]. Without Shift, one click moves the caret, two clicks select the word and three clicks select the line.
     * Every press closes completion and the context menu, hides the hover tooltip and requests focus.
     */
    public fun pointerPressed(offset: Int, clickCount: Int, modifiers: Set<KeyModifier>)

    /**
     * Handles the pointer at text [offset] while the primary button is held after [pointerPressed]. This is drag
     * selection. The view scrolls automatically at the edges of the viewport and keeps reporting the offset under the
     * pointer. The selection extends from the press position. It extends by words after a double click and by lines
     * after a triple click. Without a preceding press, or after a press that went to a definition, it does nothing.
     */
    public fun pointerDragged(offset: Int)

    /** Handles the release of the primary button. It ends the drag that [pointerPressed] started. */
    public fun pointerReleased()

    /**
     * Reports that the pointer rests over text [offset]. Null means that it left the text or moved over the gutter.
     *
     * After the pointer rested for [CodeEditorState.HOVER_DELAY], the state shows the hover content through
     * `overlays.showHover`. The delay runs in the editor's scope, so tests use virtual time. The content is the
     * diagnostics at the position, or else the language hover. The language hover is computed on the background
     * dispatcher, after a bounded wait for a parse of the current version. Moving to another offset starts the delay
     * again.
     *
     * The tooltip stays while the pointer is on its range or on the tooltip ([EditorPopupModel.pointerOverTooltip]).
     * After the pointer left both, the tooltip is hidden after a short grace period, because the way onto the tooltip
     * leaves the text first. Typing hides it at once.
     */
    public fun pointerHover(offset: Int?)

    /**
     * Opens the context menu for a right-click at text [offset]. The menu is shown at [x] and [y] in the coordinate
     * space of the `EditorOverlayHost`. If [offset] lies outside the primary selection, the caret first moves to
     * [offset], so that "Go to Definition" and "Toggle Comment" act where the user clicked. Then it calls
     * `overlays.openContextMenu`.
     */
    public fun openContextMenuAt(offset: Int, x: Float, y: Float)
}

/**
 * The state holder of one code editor. It bundles the document session, the language services, the models and the
 * overlay and viewport state. It is plain Kotlin without Compose and can be unit-tested. `CodeEditor` renders it and
 * sends intents to it. There is one instance per open editor tab, and [close] cancels its background work.
 *
 * It plays three roles. It is the [EditorViewModel] for `CodeEditor`, the [EditorPopupModel] for the popups and the
 * [EditorHandle] for the workbench. Each consumer depends only on its own role.
 *
 * To keep coupling low, it depends on the `DocumentSession` and not on the workspace's `OpenDocument`. The editor needs
 * no file-level state such as the dirty flag or conflicts. The workbench keeps that state.
 *
 * Input policy lives here and not in the composable, because composables contain no business logic. Key routing
 * ([handleKey]), the pointer gestures, the hover delay, the caret rule of the context menu and the focus rules are
 * intents of [EditorViewModel]. None of them need Compose, and all can be unit-tested on virtual time.
 *
 * After every [execute] and [type] that changed the carets or the text, the state sets a [scrollRequest] for the
 * primary caret. The request keeps a 3-line margin and is centred when the caret was off-screen. Actions never scroll
 * themselves. Find navigation selects matches through the editor's executor, so the same rule reveals them. This covers
 * `FindModel.next`, `FindModel.previous` and the selection made by `FindModel.setQuery`.
 *
 * Requests to the view are state, not events. A scroll or focus request stays pending until the view consumes it. So
 * requests made before `CodeEditor` is composed are not lost, for example `navigateTo` into a newly opened tab.
 */
public interface CodeEditorState : EditorViewModel, EditorPopupModel, EditorHandle {

    /** The display settings. */
    override val settings: StateFlow<EditorSettings>

    /** The current text of [session]. */
    override val snapshot: StateFlow<TextSnapshot> get() = session.text.snapshot

    /** The carets and the selection. */
    override val caret: CaretModel

    /** The ids of the registered actions that are enabled now, as described on [EditorPopupModel.enabledActions]. */
    override val enabledActions: StateFlow<Set<String>>

    /**
     * Runs the registered action [actionId], as described on [EditorPopupModel.execute]. Afterwards it reveals the
     * primary caret if the carets or the text changed.
     */
    override fun execute(actionId: String): Boolean

    /**
     * Runs [action] in the context of this editor. Examples are a command with parameters, or the replace, accept and
     * select actions of the models. Afterwards it reveals the primary caret if the carets or the text changed. This is
     * the `EditorActionExecutor` that the models of the editor receive.
     */
    public fun execute(action: EditorAction): Boolean

    /**
     * Navigates to the definition of the symbol at [offset]. A click with the primary modifier and the go-to-definition
     * action at the caret use it. It waits a bounded time for a parse of the current version, asks the language
     * services on the background dispatcher and emits the first location on [navigationRequests]. If there is no
     * location, it does nothing.
     */
    public fun goToDefinition(offset: Int)

    /** Sets [focusRequested]. */
    override fun requestFocus()

    /** Cancels the background work, such as highlighting, find and completion. Calling it again does nothing. */
    override fun close()

    /** Constants of [CodeEditorState]. */
    public companion object {
        /** How long the pointer must rest before the hover tooltip appears. */
        public val HOVER_DELAY: Duration = 600.milliseconds
    }
}

/**
 * Creates the state of an editor for [session]. The state runs in its own child scope of [scope].
 *
 * @param scope the parent scope. Its dispatcher is the UI thread, which is the only writer of the text.
 * @param clipboard the clipboard. The app passes the system clipboard from `io.karpfen.ide.ui.editor.view.systemClipboard()`.
 * @param settings the display settings, usually derived from the IDE settings.
 * @param keymap the shortcuts. Through `EditorKeymap.platform` it also sets the platform whose primary modifier the
 *   pointer gestures use. The workbench passes its single `WorkbenchServices.editorKeymap`, which also gives the menu
 *   commands their shortcuts. So editors and menus share one source of shortcuts.
 * @param actions the registered actions that keys and menus run.
 * @param backgroundDispatcher the dispatcher for highlighting, bracket matching, find, completion, hover and definition
 *   lookups. The models get it too. Tests pass a test dispatcher, so the work runs on virtual time.
 */
public fun CodeEditorState(
    session: DocumentSession,
    languageServices: LanguageServices,
    scope: CoroutineScope,
    clipboard: ClipboardPort,
    settings: StateFlow<EditorSettings> = MutableStateFlow(EditorSettings()),
    keymap: EditorKeymap = defaultEditorKeymap(),
    actions: EditorActionRegistry = EditorActionRegistry(),
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
): CodeEditorState =
    DefaultCodeEditorState(session, languageServices, scope, clipboard, settings, keymap, actions, backgroundDispatcher)
