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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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

/** Highlight spans for one text [version] (spans in that version's coordinates; the view maps older ones forward). */
public data class HighlightState(public val version: Long, public val spans: List<HighlightSpan>)

/**
 * The bracket pair at the primary caret computed for text [version] ([match] in that version's coordinates, null if
 * there is none). The view draws it only while [version] is the current one (or maps it forward like highlights).
 */
public data class BracketMatchState(public val version: Long, public val match: BracketMatch?)

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
 * Requests to the view are *state*, not events: a scroll or focus request stays pending until the view consumes it,
 * so requests made before `CodeEditor` is composed (e.g. `navigateTo` into a newly opened tab) are not lost.
 */
public interface CodeEditorState : EditorPopupModel, AutoCloseable {

    /** The edited document. */
    public val session: DocumentSession

    /** Language features of the workspace. */
    public val services: LanguageServices

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
    public val highlights: StateFlow<HighlightState>

    /** The bracket pair at the primary caret, computed on the background dispatcher. */
    public val bracketMatch: StateFlow<BracketMatchState>

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
     * Navigation targets for `CodeEditor`'s `onNavigate` (go to definition, Cmd/Ctrl+click), with versioned ranges
     * ([Location.version]). An event stream: `CodeEditor` collects it while composed; definition requests only arise
     * from a composed editor (keys, mouse, context menu, the active tab's menu command).
     */
    public val navigationRequests: SharedFlow<Location>

    /** Runs [action] (e.g. a parameterized command or a model's replace/accept action) in this editor's context. */
    public fun execute(action: EditorAction): Boolean

    /** Types [text] at the caret (text input path: typing action, then completion auto-trigger). */
    public fun type(text: String)

    /** Scrolls so that [offset] is visible, centred if [center] (spec §7.2), by setting [scrollRequest]. */
    public fun scrollTo(offset: Int, center: Boolean = false)

    /**
     * Navigates to the definition of the symbol at [offset]: waits (bounded) for a parse of the current version, asks
     * [services] on the background dispatcher and emits the first location on [navigationRequests]; no-op if none.
     */
    public fun goToDefinition(offset: Int)

    /**
     * What to show when hovering [offset]: the diagnostics at the position, else the language hover (computed on the
     * background dispatcher after waiting, bounded, for a parse of the current version); null for nothing.
     */
    public suspend fun hoverAt(offset: Int): HoverContent?

    /** Sets [focusRequested]. */
    override fun requestFocus()

    /** The current document as input for [services] (always consistent, see `LanguageDocument.of`). */
    public fun languageDocument(): LanguageDocument = LanguageDocument.of(session)

    /** Cancels background work (highlighting, find, completion). Idempotent. */
    override fun close()
}

/**
 * Creates the state of an editor for [session] with its own child scope of [scope].
 *
 * @param scope parent scope; its dispatcher is the UI thread, the single writer of the text (spec §1).
 * @param clipboard the system clipboard (`io.karpfen.ide.ui.editor.view.systemClipboard()` in the app).
 * @param settings display settings, usually derived from the IDE settings.
 * @param backgroundDispatcher where highlighting, bracket matching, hover and definition lookups run; tests pass a
 *   test dispatcher so they run on virtual time.
 */
public fun CodeEditorState(
    session: DocumentSession,
    services: LanguageServices,
    scope: CoroutineScope,
    clipboard: ClipboardPort,
    settings: StateFlow<EditorSettings> = MutableStateFlow(EditorSettings()),
    platform: HostPlatform = HostPlatform.current(),
    keymap: EditorKeymap = defaultEditorKeymap(platform),
    actions: EditorActionRegistry = EditorActionRegistry(),
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
): CodeEditorState =
    DefaultCodeEditorState(session, services, scope, clipboard, settings, keymap, actions, backgroundDispatcher)
