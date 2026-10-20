package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.HighlightSpan
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.ui.editor.model.action.ClipboardPort
import io.karpfen.ide.ui.editor.model.action.EditorAction
import io.karpfen.ide.ui.editor.model.action.EditorActionRegistry
import io.karpfen.ide.ui.editor.model.action.EditorKeymap
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import io.karpfen.ide.ui.editor.model.completion.CompletionSessionModel
import io.karpfen.ide.ui.editor.model.find.FindModel
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import kotlinx.coroutines.CoroutineScope
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

/** A request to scroll so that [offset] is visible (centred when [center]); processed by the view. */
public data class ScrollRequest(public val offset: Int, public val center: Boolean)

/**
 * The state holder of one code editor (spec §7.2): bundles the document session, language services, the Compose-free
 * models and the overlay/viewport state. Plain Kotlin (no Compose), unit-testable; `CodeEditor` renders it and sends
 * intents to it. One instance per open editor tab; [close] cancels its background work.
 *
 * Deviation from spec §7.2 (low coupling, spec §1a): it depends on `DocumentSession` instead of `OpenDocument`; the
 * workbench keeps the file-level state (dirty flag, conflicts).
 */
public interface CodeEditorState : AutoCloseable {

    /** The edited document. */
    public val session: DocumentSession

    /** Language features of the workspace. */
    public val services: LanguageServices

    /** Display settings. */
    public val settings: StateFlow<EditorSettings>

    /** Carets and selection. */
    public val caret: CaretModel

    /** Find/replace. */
    public val find: FindModel

    /** Completion session. */
    public val completion: CompletionSessionModel

    /** Key-bound and menu actions. */
    public val actions: EditorActionRegistry

    /** Shortcuts → action ids. */
    public val keymap: EditorKeymap

    /** Open overlays (find bar, go-to-line, hover, context menu). */
    public val overlays: EditorOverlayController

    /** Scroll position and geometry. */
    public val viewport: EditorViewport

    /** Highlight spans of the latest tokens/parse, computed off the UI thread. */
    public val highlights: StateFlow<HighlightState>

    /** The bracket pair at the primary caret, or null. */
    public val bracketMatch: StateFlow<BracketMatch?>

    /** Scroll requests for the view ([scrollTo], caret reveal). */
    public val scrollRequests: SharedFlow<ScrollRequest>

    /** Navigation targets for `CodeEditor`'s `onNavigate` (go to definition, Cmd/Ctrl+click). */
    public val navigationRequests: SharedFlow<Location>

    /** Emitted when keyboard focus should return to the text area (e.g. Escape in the find bar). */
    public val focusRequests: SharedFlow<Unit>

    /** Runs the registered action [actionId]; returns whether it did something. */
    public fun execute(actionId: String): Boolean

    /** Runs [action] (e.g. a parameterized command). */
    public fun execute(action: EditorAction): Boolean

    /** Types [text] at the caret (text input path: typing action, then completion auto-trigger). */
    public fun type(text: String)

    /** Scrolls so that [offset] is visible, centred if [center] (spec §7.2). */
    public fun scrollTo(offset: Int, center: Boolean = false)

    /** Navigates to the definition of the symbol at [offset] (emits on [navigationRequests]; no-op if none). */
    public fun goToDefinition(offset: Int)

    /** What to show when hovering [offset]: diagnostics at the position, else the language hover; null for nothing. */
    public fun hoverAt(offset: Int): HoverContent?

    /** Asks the view to focus the text area. */
    public fun requestFocus()

    /** The current document as input for [services]. */
    public fun languageDocument(): LanguageDocument = LanguageDocument.of(session)

    /** Cancels background work (highlighting, find, completion). Idempotent. */
    override fun close()
}

/**
 * Creates the state of an editor for [session] with its own child scope of [scope].
 *
 * @param clipboard the system clipboard (`io.karpfen.ide.ui.editor.view.systemClipboard()` in the app).
 * @param settings display settings, usually derived from the IDE settings.
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
): CodeEditorState = DefaultCodeEditorState(session, services, scope, clipboard, settings, keymap, actions)
