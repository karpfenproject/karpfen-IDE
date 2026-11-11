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
    /** The `CodeEditor` node. It carries the text-input semantics. */
    const val EDITOR = "e2e.editor"

    /** A box that fills the text area, which is the space of the overlay host. It lies below the popups. */
    const val TEXT_AREA = "e2e.textArea"
}

/**
 * The real [EditorPopups] as the editor's overlays, decorated for tests. Tests learn the [host] from it to compute click
 * positions, and they find the text area by [EditorE2eTags.TEXT_AREA]. The tagged box is composed first, so the popups
 * lie above it. The box has no pointer input of its own, so clicks reach the text area.
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
 * A composed `CodeEditor` together with the real `EditorPopups`. This is the end-to-end setup that the tests of the view
 * and of the popups do not cover, because each of them uses a fake of the other side.
 *
 * It uses a real KStates [DocumentSession] with the registry's tokenizer and parser. The language services are the real
 * ones of core-lang over an empty project analysis, so state names, macros and keywords come from the file itself. The
 * models are real and the keymap is the macOS one. Session pipelines and the editor's background work run inline.
 * Delays such as the parse debounce, the hover delay and the caret blink run on the composition's clock, which is the
 * test's main clock.
 *
 * Key events that the editor does not consume reach a parent handler and are recorded in [unconsumedKeys]. These are
 * the strokes a window-menu accelerator would see.
 */
@OptIn(ExperimentalTestApi::class)
class EditorWithPopups(private val test: ComposeUiTest) {

    /** The overlays. These are the real popups with host recording. */
    val overlays = HostRecordingPopups()

    /** The clipboard. */
    val clipboard = TestClipboard()

    /** Targets passed to `onNavigate`. */
    val navigated: MutableList<Location> = mutableListOf()

    /** Key-down events that nobody consumed. Modifier keys on their own are never consumed and are not recorded. */
    val unconsumedKeys: MutableList<Key> = mutableListOf()

    /** Provider failures behind the language-services facade. The tests expect this list to stay empty. */
    val providerFailures: MutableList<ProviderFailure> = Collections.synchronizedList(mutableListOf())

    /** The editor state. The composition sets it. */
    lateinit var state: CodeEditorState
        private set

    /** The plain text. */
    val plain: String get() = state.session.text.snapshot.value.text

    /** The editor node. */
    val editor: SemanticsNodeInteraction get() = test.onNodeWithTag(EditorE2eTags.EDITOR)

    /** The text-area node. Positions in its coordinates are host coordinates. */
    val textArea: SemanticsNodeInteraction get() = test.onNodeWithTag(EditorE2eTags.TEXT_AREA)

    /** The overlay host. */
    val host: EditorOverlayHost get() = checkNotNull(overlays.host) { "not composed yet" }

    /** A point inside the character at [offset] in host coordinates, one pixel right of its left edge and vertically centred. */
    fun pointAt(offset: Int): Offset =
        checkNotNull(host.boundsOf(offset)) { "offset $offset is not laid out" }.let { Offset(it.left + 1f, it.center.y) }

    /** Types [text] one character at a time like a keyboard. Each character is one `performTextInput`. */
    fun typeChars(text: String) {
        text.forEach { character ->
            editor.performTextInput(character.toString())
            test.waitForIdle()
        }
    }

    /** The text of the line that contains [offset], without its line separator. */
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

    /** Composes the editor over [initialText] in a box of [width] by [height]. */
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

    /** Holds the set of modifier keys. */
    private companion object {
        /** Keys that only hold a modifier. Pressing one of them alone is not a stroke the tests care about. */
        val MODIFIER_KEYS: Set<Key> = setOf(
            Key.MetaLeft, Key.MetaRight, Key.CtrlLeft, Key.CtrlRight, Key.ShiftLeft, Key.ShiftRight, Key.AltLeft, Key.AltRight,
        )
    }
}
