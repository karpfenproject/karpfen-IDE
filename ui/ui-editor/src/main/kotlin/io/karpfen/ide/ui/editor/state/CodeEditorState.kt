package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.HighlightSpan
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.action.ClipboardPort
import io.karpfen.ide.ui.editor.model.action.EditorAction
import io.karpfen.ide.ui.editor.model.action.EditorActionRegistry
import io.karpfen.ide.ui.editor.model.action.EditorKeymap
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.foundation.keys.HostPlatform
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
 * Display settings of an editor (spec §7.2).
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
 * Highlight spans for one text [version] (spans in that version's coordinates; the view maps older ones forward).
 * An immutable result (`*Snapshot`, naming rule of the work-package plan).
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
 * The state holder of one code editor (spec §7.2): bundles the document session, language services, the Compose-free
 * models and the overlay/viewport state. Plain Kotlin (no Compose), unit-testable; `CodeEditor` renders it and sends
 * intents to it. One instance per open editor tab; [close] cancels its background work.
 *
 * Deviation from spec §7.2 (low coupling, spec §1a): it depends on `DocumentSession` instead of `OpenDocument`; the
 * workbench keeps the file-level state (dirty flag, conflicts).
 *
 * Input policy lives here, not in the composable (spec §1a: composables contain no business logic): the view converts
 * raw input into the intents [handleKey], [type], [pointerHover], [openContextMenuAt], [goToDefinition] and caret
 * calls, so key routing (decision 15 of the work-package plan), the hover delay and the context-menu caret rule are
 * unit-testable on virtual time.
 *
 * Caret reveal: after every [execute] and [type] that changed the carets or the text, the state sets a [scrollRequest]
 * for the primary caret (3-line margin); actions never scroll themselves.
 *
 * Requests to the view are *state*, not events: a scroll or focus request stays pending until the view consumes it,
 * so requests made before `CodeEditor` is composed (e.g. `navigateTo` into a newly opened tab) are not lost.
 */
public interface CodeEditorState : EditorPopupModel, AutoCloseable {

    /** The edited document. */
    public val session: DocumentSession

    /** Language features of the workspace. */
    public val languageServices: LanguageServices

    /** Display settings. */
    public val settings: StateFlow<EditorSettings>

    /** The current text of [session]. */
    override val snapshot: StateFlow<TextSnapshot> get() = session.text.snapshot

    /** Key-bound and menu actions. */
    public val actions: EditorActionRegistry

    /** Shortcuts → action ids. */
    public val keymap: EditorKeymap

    /** Scroll position and geometry. */
    public val viewport: EditorViewport

    /** Highlight spans of the latest tokens/parse, computed on the background dispatcher. */
    public val highlights: StateFlow<HighlightSnapshot>

    /** The bracket pair at the primary caret, computed on the background dispatcher. */
    public val bracketMatch: StateFlow<BracketMatchSnapshot>

    /**
     * The latest scroll request ([scrollTo], caret reveal) not yet consumed by the view, or null. A newer request
     * replaces an older pending one.
     */
    public val scrollRequest: StateFlow<ScrollRequest?>

    /** Marks [request] as handled: clears [scrollRequest] if it is still the pending request. */
    public fun consumeScrollRequest(request: ScrollRequest)

    /** True while a focus request ([requestFocus]) is pending; the view focuses the text area and consumes it. */
    public val focusRequested: StateFlow<Boolean>

    /** Marks the pending focus request as handled. */
    public fun consumeFocusRequest()

    /**
     * Navigation targets (go to definition, Cmd/Ctrl+click), with versioned ranges ([Location.version],
     * [Location.lineage]). An event stream without replay: its owner collects it for the editor's whole life — the
     * workbench mediator from creating the tab's editor until closing it (so a lookup finishing after a tab switch is
     * not lost), `CodeEditor`'s `onNavigate` only in simple embeddings without a workbench.
     */
    public val navigationRequests: SharedFlow<Location>

    /**
     * Runs the registered action [actionId] (see [EditorPopupModel.execute]); reveals the primary caret afterwards if
     * the carets or the text changed.
     */
    override fun execute(actionId: String): Boolean

    /**
     * Runs [action] (e.g. a parameterized command or a model's replace/accept action) in this editor's context; reveals
     * the primary caret afterwards if the carets or the text changed.
     */
    public fun execute(action: EditorAction): Boolean

    /**
     * Types [text] at the caret (text input path: typing action, then completion auto-trigger) and reveals the caret.
     */
    public fun type(text: String)

    /**
     * Handles a key stroke of the focused text area (decision 15 of the work-package plan): first
     * `completion.handleKey`, then the [keymap]'s action, executed if registered and enabled. Returns true iff the
     * stroke was handled (completion consumed it, or a bound action was enabled and executed); the view consumes
     * exactly those strokes, so unhandled ones reach menu accelerators and one key press runs one action.
     */
    public fun handleKey(stroke: KeyStroke): Boolean

    /**
     * The pointer rests over text [offset] (null: it left the text or moved over the gutter). After [HOVER_DELAY] of
     * rest (a delay in the editor's scope, so tests use virtual time) the state shows [hoverAt]'s content through
     * `overlays.showHover`; moving to another offset restarts the delay, and leaving or typing hides the tooltip.
     */
    public fun pointerHover(offset: Int?)

    /**
     * Opens the context menu for a right-click at text [offset], shown at ([x], [y]) in the `EditorOverlayHost`
     * coordinate space: unless [offset] lies inside the primary selection, the caret first moves to [offset] (so "Go to
     * Definition" and "Toggle Comment" act where the user clicked), then `overlays.openContextMenu`.
     */
    public fun openContextMenuAt(offset: Int, x: Float, y: Float)

    /** Scrolls so that [offset] is visible, centred if [center] (spec §7.2), by setting [scrollRequest]. */
    public fun scrollTo(offset: Int, center: Boolean = false)

    /**
     * Navigates to the definition of the symbol at [offset] (Cmd/Ctrl+click, the go-to-definition action at the caret):
     * waits (bounded) for a parse of the current version, asks [languageServices] on the background dispatcher and
     * emits the first location on [navigationRequests]; no-op if none.
     */
    public fun goToDefinition(offset: Int)

    /**
     * What to show when hovering [offset]: the diagnostics at the position, else the language hover (computed on the
     * background dispatcher after waiting, bounded, for a parse of the current version); null for nothing.
     */
    public suspend fun hoverAt(offset: Int): HoverContent?

    /** Sets [focusRequested]. */
    override fun requestFocus()

    /** The current document as input for [languageServices] (always consistent, see `LanguageDocument.of`). */
    public fun languageDocument(): LanguageDocument = LanguageDocument.of(session)

    /** Cancels background work (highlighting, find, completion). Idempotent. */
    override fun close()

    /** Constants of [CodeEditorState]. */
    public companion object {
        /** How long the pointer must rest before the hover tooltip appears (spec §7.2: 600 ms). */
        public val HOVER_DELAY: Duration = 600.milliseconds
    }
}

/**
 * Creates the state of an editor for [session] with its own child scope of [scope].
 *
 * @param scope parent scope; its dispatcher is the UI thread, the single writer of the text (spec §1).
 * @param clipboard the system clipboard (`io.karpfen.ide.ui.editor.view.systemClipboard()` in the app).
 * @param settings display settings, usually derived from the IDE settings.
 * @param keymap the shortcuts; the workbench passes its one `WorkbenchServices.editorKeymap`, which also gives the menu
 *   commands their shortcuts (decision 15 of the work-package plan).
 * @param backgroundDispatcher where highlighting, bracket matching, find, completion, hover and definition lookups run
 *   (the models get it too); tests pass a test dispatcher so they run on virtual time.
 */
public fun CodeEditorState(
    session: DocumentSession,
    languageServices: LanguageServices,
    scope: CoroutineScope,
    clipboard: ClipboardPort,
    settings: StateFlow<EditorSettings> = MutableStateFlow(EditorSettings()),
    platform: HostPlatform = HostPlatform.current(),
    keymap: EditorKeymap = defaultEditorKeymap(platform),
    actions: EditorActionRegistry = EditorActionRegistry(),
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
): CodeEditorState =
    DefaultCodeEditorState(session, languageServices, scope, clipboard, settings, keymap, actions, backgroundDispatcher)
