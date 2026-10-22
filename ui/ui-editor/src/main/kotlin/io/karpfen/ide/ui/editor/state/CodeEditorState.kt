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
 * Display settings of an editor.
 *
 * @property fontSize font size in sp.
 * @property tabDisplayWidth columns per tab stop when rendering tab characters.
 * @property showLineNumbers whether the gutter shows one-based line numbers.
 */
public data class EditorSettings(
    public val fontSize: Float = 13f,
    public val tabDisplayWidth: Int = 4,
    public val showLineNumbers: Boolean = true,
) {
    init {
        require(fontSize > 0f) { "fontSize must be positive, was $fontSize" }
        require(tabDisplayWidth >= 1) { "tabDisplayWidth must be >= 1, was $tabDisplayWidth" }
    }
}

/**
 * Highlight spans for one text [version] (spans in that version's coordinates; the view maps older ones forward with
 * `EditableText.mapRange`). An immutable, versioned result; such results of the UI modules are named `*Snapshot`.
 */
public data class HighlightSnapshot(public val version: Long, public val spans: List<HighlightSpan>)

/**
 * The bracket pair at the primary caret computed for text [version] ([match] in that version's coordinates, null if
 * there is none). The view draws it only while [version] is the current one (or maps it forward like highlights).
 */
public data class BracketMatchSnapshot(public val version: Long, public val match: BracketMatch?)

/** A request to scroll so that [offset] is visible (centred when [center]); processed by the view. */
public data class ScrollRequest(public val offset: Int, public val center: Boolean)

/**
 * What the workbench may use of an editor (interface segregation): the document, the carets, focus and enablement for
 * command forwarding, navigation requests and the navigation intents. The workbench's sub-states (`OutlineState`,
 * `StatusBarState`) and the mediator's collaborators depend on this role, so their tests fake only it. Used from the UI
 * thread.
 */
public interface EditorHandle : AutoCloseable {

    /** The edited document. */
    public val session: DocumentSession

    /** Carets and selection (status bar position, outline highlight, navigation). */
    public val caret: CaretModel

    /** Display settings (the status bar's display column uses `tabDisplayWidth`). */
    public val settings: StateFlow<EditorSettings>

    /** Ids of the registered actions that are enabled now (see [EditorPopupModel.enabledActions]). */
    public val enabledActions: StateFlow<Set<String>>

    /**
     * True while the editor's text area has keyboard focus (reported by the view through
     * [EditorViewModel.onFocusChanged]). The workbench forwards text-editing commands from the menu only while the
     * active editor is focused (see `WorkbenchState.registerCommands`).
     */
    public val isFocused: StateFlow<Boolean>

    /**
     * Navigation targets (go to definition, Cmd/Ctrl+click), with versioned ranges ([Location.version],
     * [Location.lineage]). An event stream without replay: its owner collects it for the editor's whole life — the
     * workbench mediator from creating the tab's editor until closing it (so a lookup finishing after a tab switch is
     * not lost), `CodeEditor`'s `onNavigate` only in simple embeddings without a workbench.
     */
    public val navigationRequests: SharedFlow<Location>

    /**
     * Runs the registered action [actionId] if it is enabled; reveals the primary caret afterwards if the carets or the
     * text changed. Returns whether it did something.
     */
    public fun execute(actionId: String): Boolean

    /** Scrolls so that [offset] is visible, centred if [center], by setting a pending scroll request. */
    public fun scrollTo(offset: Int, center: Boolean = false)

    /** Asks the view to focus the text area (a pending request, so it also works before the editor is composed). */
    public fun requestFocus()

    /** Cancels background work (highlighting, find, completion). Idempotent. */
    override fun close()
}

/**
 * What `CodeEditor` needs beyond the popups' model (interface segregation): geometry, render results, pending view
 * requests and the Compose-free input intents. The view converts raw input into these intents and contains no input
 * policy (composables contain no business logic), so the input rules are unit-tested without Compose.
 */
public interface EditorViewModel {

    /** Display settings. */
    public val settings: StateFlow<EditorSettings>

    /** Scroll position and geometry. */
    public val viewport: EditorViewport

    /**
     * Highlight spans, computed on the background dispatcher from the session's tokens and parse:
     * when the latest parse has the tokens' version, `LanguageServices.highlight` (lexical styles overlaid with the
     * semantic ones); while the parse lags the tokens (every keystroke until the debounced parse arrives), the fresh
     * lexical spans overlaid (`overlayHighlights` of core-lang) with the semantic-style spans (`HighlightStyle.isSemantic`)
     * of the previous snapshot mapped to the tokens' version with `EditableText.mapRange` (null or empty results
     * dropped), so semantic colours of untouched names do not flicker while typing.
     */
    public val highlights: StateFlow<HighlightSnapshot>

    /** The bracket pair at the primary caret, computed on the background dispatcher. */
    public val bracketMatch: StateFlow<BracketMatchSnapshot>

    /**
     * The latest scroll request ([EditorHandle.scrollTo], caret reveal) not yet consumed by the view, or null. A newer
     * request replaces an older pending one.
     */
    public val scrollRequest: StateFlow<ScrollRequest?>

    /** Marks [request] as handled: clears [scrollRequest] if it is still the pending request. */
    public fun consumeScrollRequest(request: ScrollRequest)

    /** True while a focus request ([EditorHandle.requestFocus]) is pending; the view focuses the text area and consumes it. */
    public val focusRequested: StateFlow<Boolean>

    /** Marks the pending focus request as handled. */
    public fun consumeFocusRequest()

    /**
     * The text area gained or lost keyboard focus (updates [EditorHandle.isFocused]). On focus loss the state closes
     * completion and hides the hover tooltip; the context menu and the find bar are popups of their own and
     * stay open.
     */
    public fun onFocusChanged(focused: Boolean)

    /**
     * Types [text] at the caret (text input path: typing action, then completion auto-trigger) and reveals the caret.
     */
    public fun type(text: String)

    /**
     * Handles a key stroke of the focused text area: first `completion.handleKey`, then the keymap's action, executed
     * if registered and enabled. Returns true iff the stroke was handled (completion consumed it, or a bound action was
     * enabled and executed); the view consumes exactly those strokes. The focused component thus handles its strokes
     * first, and a window-menu accelerator only sees a stroke nobody consumed (Swing key bindings skip consumed events;
     * the macOS screen menu bar delivers its key equivalents as key events first), so one key press runs one action.
     */
    public fun handleKey(stroke: KeyStroke): Boolean

    /**
     * A primary-button press at text [offset] (hit-tested by the view, in the current text's coordinates) with
     * [clickCount] (1, 2, 3; higher counts count as 3) and the physical [modifiers] held. The state decides (input
     * policy, unit-tested on virtual time): the platform's primary modifier (`HostPlatform.primaryModifier` of the
     * editor's keymap) with a single click → go to definition at [offset] (caret unchanged); otherwise SHIFT → extend
     * the selection to [offset]; else 1 → move the caret, 2 → select the word, 3 → select the line. Every press closes
     * completion and the context menu, hides the hover and requests focus.
     */
    public fun pointerPressed(offset: Int, clickCount: Int, modifiers: Set<KeyModifier>)

    /**
     * The pointer is at text [offset] while the primary button is held after [pointerPressed] (drag selection; the view
     * auto-scrolls at the viewport edges and keeps reporting the offset under the pointer): extends the selection from
     * the press position, by words after a double click and by lines after a triple click. No-op without a preceding
     * press or after a go-to-definition press.
     */
    public fun pointerDragged(offset: Int)

    /** The primary button was released; ends the drag started by [pointerPressed]. */
    public fun pointerReleased()

    /**
     * The pointer rests over text [offset] (null: it left the text or moved over the gutter). After
     * [CodeEditorState.HOVER_DELAY] of rest (a delay in the editor's scope, so tests use virtual time) the state shows
     * the hover content — the diagnostics at the position, else the language hover, computed on the background
     * dispatcher after waiting (bounded) for a parse of the current version — through `overlays.showHover`; moving to
     * another offset restarts the delay. The tooltip stays while the pointer is on its range or on the tooltip
     * ([EditorPopupModel.pointerOverTooltip]); after the pointer left both it is hidden after a short grace period (the
     * way onto the tooltip leaves the text first). Typing hides it at once.
     */
    public fun pointerHover(offset: Int?)

    /**
     * Opens the context menu for a right-click at text [offset], shown at ([x], [y]) in the `EditorOverlayHost`
     * coordinate space: unless [offset] lies inside the primary selection, the caret first moves to [offset] (so "Go to
     * Definition" and "Toggle Comment" act where the user clicked), then `overlays.openContextMenu`.
     */
    public fun openContextMenuAt(offset: Int, x: Float, y: Float)
}

/**
 * The state holder of one code editor: bundles the document session, language services, the Compose-free
 * models and the overlay/viewport state. Plain Kotlin (no Compose), unit-testable; `CodeEditor` renders it and sends
 * intents to it. One instance per open editor tab; [close] cancels its background work.
 *
 * It plays three roles: [EditorViewModel] for `CodeEditor`, [EditorPopupModel] for the popups, [EditorHandle] for
 * the workbench; each consumer depends on its role only.
 *
 * For low coupling it depends on the `DocumentSession`, not on the workspace's `OpenDocument`: the editor needs no
 * file-level state, which the workbench keeps (dirty flag, conflicts).
 *
 * Input policy lives here, not in the composable (composables contain no business logic): key routing ([handleKey]),
 * the pointer gestures, the hover delay, the context-menu caret rule and the focus rules are Compose-free intents of
 * [EditorViewModel], unit-testable on virtual time.
 *
 * Caret reveal: after every [execute] and [type] that changed the carets or the text, the state sets a
 * [scrollRequest] for the primary caret (3-line margin; centred when the caret was off-screen); actions never scroll
 * themselves. Find navigation (`FindModel.next`/`previous` and the selection of `setQuery`) selects matches through
 * the editor's executor, so it is revealed by the same rule.
 *
 * Requests to the view are *state*, not events: a scroll or focus request stays pending until the view consumes it,
 * so requests made before `CodeEditor` is composed (e.g. `navigateTo` into a newly opened tab) are not lost.
 */
public interface CodeEditorState : EditorViewModel, EditorPopupModel, EditorHandle {

    /** Display settings. */
    override val settings: StateFlow<EditorSettings>

    /** The current text of [session]. */
    override val snapshot: StateFlow<TextSnapshot> get() = session.text.snapshot

    /** Carets and selection. */
    override val caret: CaretModel

    /** Ids of the registered actions that are enabled now (see [EditorPopupModel.enabledActions]). */
    override val enabledActions: StateFlow<Set<String>>

    /**
     * Runs the registered action [actionId] (see [EditorPopupModel.execute]); reveals the primary caret afterwards if
     * the carets or the text changed.
     */
    override fun execute(actionId: String): Boolean

    /**
     * Runs [action] (e.g. a parameterized command or a model's replace/accept/select action) in this editor's context;
     * reveals the primary caret afterwards if the carets or the text changed. This is the `EditorActionExecutor` the
     * editor's models receive.
     */
    public fun execute(action: EditorAction): Boolean

    /**
     * Navigates to the definition of the symbol at [offset] (primary-modifier click, the go-to-definition action at the
     * caret): waits (bounded) for a parse of the current version, asks the language services on the background
     * dispatcher and emits the first location on [navigationRequests]; no-op if none.
     */
    public fun goToDefinition(offset: Int)

    /** Sets [focusRequested]. */
    override fun requestFocus()

    /** Cancels background work (highlighting, find, completion). Idempotent. */
    override fun close()

    /** Constants of [CodeEditorState]. */
    public companion object {
        /** How long the pointer must rest before the hover tooltip appears. */
        public val HOVER_DELAY: Duration = 600.milliseconds
    }
}

/**
 * Creates the state of an editor for [session] with its own child scope of [scope].
 *
 * @param scope parent scope; its dispatcher is the UI thread, the single writer of the text.
 * @param clipboard the system clipboard (`io.karpfen.ide.ui.editor.view.systemClipboard()` in the app).
 * @param settings display settings, usually derived from the IDE settings.
 * @param keymap the shortcuts, and through `EditorKeymap.platform` the platform whose primary modifier the pointer
 *   gestures use; the workbench passes its one `WorkbenchServices.editorKeymap`, which also gives the menu commands
 *   their shortcuts, so editors and menus share one source of shortcuts.
 * @param backgroundDispatcher where highlighting, bracket matching, find, completion, hover and definition lookups run
 *   (the models get it too); tests pass a test dispatcher so they run on virtual time.
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
