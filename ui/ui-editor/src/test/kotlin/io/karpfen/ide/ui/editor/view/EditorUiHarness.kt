package io.karpfen.ide.ui.editor.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.session.DocumentId
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.editor.overlay.EditorOverlayHost
import io.karpfen.ide.ui.editor.overlay.EditorOverlays
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.state.FakeLanguageServices
import io.karpfen.ide.ui.editor.state.TestClipboard
import io.karpfen.ide.ui.editor.theme.lightEditorTheme
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path

/** Test tags of the composed editor. */
object EditorTestTags {
    /** The `CodeEditor` node (carries the text-input semantics). */
    const val EDITOR = "editor"

    /** A box filling the text area (the overlay host's space), drawn by [RecordingOverlays]. */
    const val TEXT_AREA = "textArea"
}

/**
 * An [EditorOverlays] for view tests, independent of the real popups: records the host it is given and marks the text
 * area with a tagged, input-transparent box, so tests can click at `host.boundsOf(offset)`.
 */
class RecordingOverlays : EditorOverlays {
    /** The host of the last composition. */
    var host: EditorOverlayHost? = null
        private set

    @Composable
    override fun Header(host: EditorOverlayHost) = Unit

    @Composable
    override fun Popups(host: EditorOverlayHost) {
        this.host = host
        Box(Modifier.fillMaxSize().testTag(EditorTestTags.TEXT_AREA))
    }
}

/**
 * A composed `CodeEditor` for headless UI tests: a real session (registry tokenizer and parser, running inline) and
 * editor state in the composition's scope, so delays — the parse debounce, the hover delay, the caret blink — run on
 * the test's main clock; a [FakeLanguageServices]; the macOS keymap (Cmd is the primary modifier); [RecordingOverlays].
 */
@OptIn(ExperimentalTestApi::class)
class EditorUi(private val test: ComposeUiTest, val services: FakeLanguageServices) {

    /** The overlays slot. */
    val overlays = RecordingOverlays()

    /** The clipboard. */
    val clipboard = TestClipboard()

    /** Targets passed to `onNavigate`. */
    val navigated = mutableListOf<Location>()

    /** The editor state (set by the composition). */
    lateinit var state: CodeEditorState
        private set

    /** The edited text. */
    val text: EditableText get() = state.session.text

    /** The plain text. */
    val plain: String get() = text.snapshot.value.text

    /** The editor node. */
    val editor: SemanticsNodeInteraction get() = test.onNodeWithTag(EditorTestTags.EDITOR)

    /** The text-area node (positions in its coordinates are text-area coordinates). */
    val textArea: SemanticsNodeInteraction get() = test.onNodeWithTag(EditorTestTags.TEXT_AREA)

    /** The overlay host. */
    val host: EditorOverlayHost get() = checkNotNull(overlays.host) { "not composed yet" }

    /** The centre of the character at [offset] in text-area coordinates. */
    fun centerOf(offset: Int): Offset = checkNotNull(host.boundsOf(offset)) { "offset $offset is not laid out" }.center

    /**
     * Composes the editor over [initialText] in a box of [width] x [height] with [slots] as overlays;
     * [beforeComposition] runs on the new state before the editor is composed.
     */
    fun compose(
        initialText: String,
        width: Dp = 600.dp,
        height: Dp = 300.dp,
        slots: EditorOverlays = overlays,
        beforeComposition: (CodeEditorState) -> Unit = {},
    ) {
        test.setContent {
            val scope = rememberCoroutineScope()
            val editorState = remember {
                val session = DocumentSession(
                    id = DocumentId.of(Path.of(System.getProperty("java.io.tmpdir"), "ui-test.kstates")),
                    support = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES),
                    text = EditableText(initialText),
                    scope = scope,
                    config = DocumentSessionConfig(tokenizeDispatcher = Dispatchers.Unconfined, parseDispatcher = Dispatchers.Unconfined),
                )
                CodeEditorState(
                    session = session,
                    languageServices = services,
                    scope = scope,
                    clipboard = clipboard,
                    keymap = defaultEditorKeymap(HostPlatform.MAC_OS),
                    backgroundDispatcher = Dispatchers.Unconfined,
                ).also {
                    state = it
                    beforeComposition(it)
                }
            }
            Box(Modifier.size(width, height)) {
                CodeEditor(
                    state = editorState,
                    modifier = Modifier.fillMaxSize().testTag(EditorTestTags.EDITOR),
                    onNavigate = { navigated += it },
                    overlays = slots,
                    theme = lightEditorTheme(),
                )
            }
        }
        test.waitForIdle()
    }
}
