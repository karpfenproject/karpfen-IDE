package io.karpfen.ide.ui.editor.e2e

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.session.DocumentId
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.services.ProviderFailure
import io.karpfen.ide.core.lang.services.defaultLanguageServices
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.editor.overlay.EditorOverlayHost
import io.karpfen.ide.ui.editor.overlay.EditorOverlays
import io.karpfen.ide.ui.editor.popup.EditorPopups
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.state.TestClipboard
import io.karpfen.ide.ui.editor.theme.lightEditorTheme
import io.karpfen.ide.ui.editor.view.CodeEditor
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Path
import java.util.Collections

/** Test tags of the end-to-end editor. */
object EditorE2eTags {
    /** The `CodeEditor` node (carries the text-input semantics). */
    const val EDITOR = "e2e.editor"

    /** A box filling the text area (the overlay host's space), below the popups. */
    const val TEXT_AREA = "e2e.textArea"
}

/**
 * The real [EditorPopups] as the editor's overlays, decorated so tests learn the [host] (for click positions) and find
 * the text area by [EditorE2eTags.TEXT_AREA]. The tagged box is composed first, so the popups lie above it, and it has
 * no pointer input of its own, so clicks reach the text area.
 */
class HostRecordingPopups(private val popups: EditorOverlays = EditorPopups()) : EditorOverlays {
    /** The host of the last composition. */
    var host: EditorOverlayHost? = null
        private set

    @Composable
    override fun Header(host: EditorOverlayHost) = popups.Header(host)

    @Composable
    override fun Popups(host: EditorOverlayHost) {
        this.host = host
        Box(Modifier.fillMaxSize().testTag(EditorE2eTags.TEXT_AREA))
        popups.Popups(host)
    }
}

/**
 * A composed `CodeEditor` together with the real `EditorPopups` (INTEGRATION's `ui.editor.e2e`, which neither
 * WP-EDITOR-VIEW nor WP-EDITOR-POPUPS could test): a real KStates [DocumentSession] (registry tokenizer and parser), the
 * real language services of core-lang over an empty project analysis (state names, macros and keywords come from the
 * file itself), the real models, the macOS keymap. Session pipelines and the editor's background work run inline;
 * delays (parse debounce, hover, caret blink) run on the composition's clock, i.e. the test's main clock.
 *
 * Key events that the editor does not consume reach a parent handler and are recorded in [unconsumedKeys] — the
 * strokes a window-menu accelerator would see (decision 15 of the work-package plan).
 */
@OptIn(ExperimentalTestApi::class)
class EditorWithPopups(private val test: ComposeUiTest) {

    /** The overlays: the real popups plus host recording. */
    val overlays = HostRecordingPopups()

    /** The clipboard. */
    val clipboard = TestClipboard()

    /** Targets passed to `onNavigate`. */
    val navigated: MutableList<Location> = mutableListOf()

    /** Key-down events nobody consumed (modifier keys on their own are never consumed and not recorded). */
    val unconsumedKeys: MutableList<Key> = mutableListOf()

    /** Provider failures behind the language-services facade (must stay empty). */
    val providerFailures: MutableList<ProviderFailure> = Collections.synchronizedList(mutableListOf())

    /** The editor state (set by the composition). */
    lateinit var state: CodeEditorState
        private set

    /** The plain text. */
    val plain: String get() = state.session.text.snapshot.value.text

    /** The editor node. */
    val editor: SemanticsNodeInteraction get() = test.onNodeWithTag(EditorE2eTags.EDITOR)

    /** The text-area node (positions in its coordinates are host coordinates). */
    val textArea: SemanticsNodeInteraction get() = test.onNodeWithTag(EditorE2eTags.TEXT_AREA)

    /** The overlay host. */
    val host: EditorOverlayHost get() = checkNotNull(overlays.host) { "not composed yet" }

    /** A point inside the character at [offset], in host coordinates (left half, vertically centred). */
    fun pointAt(offset: Int): Offset =
        checkNotNull(host.boundsOf(offset)) { "offset $offset is not laid out" }.let { Offset(it.left + 1f, it.center.y) }

    /** Types [text] one character at a time, like a keyboard (each character is one `performTextInput`). */
    fun typeChars(text: String) {
        text.forEach { character ->
            editor.performTextInput(character.toString())
            test.waitForIdle()
        }
    }

    /** The text of the line that contains [offset] (without its separator). */
    fun lineAt(offset: Int): String {
        val snapshot = state.session.text.snapshot.value
        return snapshot.lineText(snapshot.lineIndex.lineOf(offset))
    }

    /** True if the line of [offset] lies completely inside the viewport. */
    fun isVisible(offset: Int): Boolean {
        val line = state.session.text.snapshot.value.lineIndex.lineOf(offset)
        val viewport = state.viewport.state.value
        val top = line * viewport.lineHeight
        return top >= viewport.scrollY && top + viewport.lineHeight <= viewport.scrollY + viewport.viewportHeight
    }

    /** Composes the editor over [initialText] in a box of [width] x [height]. */
    fun compose(initialText: String, width: Dp = 640.dp, height: Dp = 320.dp) {
        test.setContent {
            val scope = rememberCoroutineScope()
            val editorState = remember {
                val session = DocumentSession(
                    id = DocumentId.of(Path.of(System.getProperty("java.io.tmpdir"), "e2e", "machine.kstates")),
                    support = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES),
                    text = EditableText(initialText),
                    scope = scope,
                    config = DocumentSessionConfig(tokenizeDispatcher = Dispatchers.Unconfined, parseDispatcher = Dispatchers.Unconfined),
                )
                CodeEditorState(
                    session = session,
                    languageServices = defaultLanguageServices(
                        MutableStateFlow(ProjectAnalysis.EMPTY),
                        onProviderFailure = { providerFailures += it },
                    ),
                    scope = scope,
                    clipboard = clipboard,
                    keymap = defaultEditorKeymap(HostPlatform.MAC_OS),
                    backgroundDispatcher = Dispatchers.Unconfined,
                ).also { state = it }
            }
            Box(
                Modifier.size(width, height).onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key !in MODIFIER_KEYS) unconsumedKeys += event.key
                    false
                },
            ) {
                CodeEditor(
                    state = editorState,
                    modifier = Modifier.fillMaxSize().testTag(EditorE2eTags.EDITOR),
                    onNavigate = { navigated += it },
                    overlays = overlays,
                    theme = lightEditorTheme(),
                )
            }
        }
        test.waitForIdle()
    }

    private companion object {
        val MODIFIER_KEYS: Set<Key> = setOf(
            Key.MetaLeft, Key.MetaRight, Key.CtrlLeft, Key.CtrlRight, Key.ShiftLeft, Key.ShiftRight, Key.AltLeft, Key.AltRight,
        )
    }
}
