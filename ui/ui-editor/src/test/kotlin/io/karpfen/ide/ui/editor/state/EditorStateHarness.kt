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

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.session.DocumentId
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.ClipboardPort
import io.karpfen.ide.ui.editor.model.action.EditorActionRegistry
import io.karpfen.ide.ui.editor.model.action.EditorKeymap
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.time.TestTimeSource

/**
 * An editor state for tests without Compose, wired like the product. It has a real [EditableText] with a test time
 * source, a real [DocumentSession] with the language registry's tokenizer and parser, the real models, a
 * [FakeLanguageServices] and an in-memory clipboard.
 *
 * Every coroutine runs on the scheduler of [testScope] in [scope]. This covers the session's pipelines, the editor's
 * scope and its background dispatcher. They run as foreground work, so `advanceUntilIdle` settles everything. The
 * `backgroundScope` of the test scope would not be advanced by it. [close] cancels the scope, and [editorTest] calls
 * [close] at the end.
 *
 * The view is simulated with [layOut], which reports a viewport like `CodeEditor` does.
 *
 * @property testScope the test scope whose scheduler runs all coroutines.
 * @param initialText the text the editor starts with.
 * @param platform the platform whose keymap the editor uses.
 * @property services the fake language services of the editor.
 * @param language the karpfen language of the document. It selects the tokenizer, the parser and the file extension.
 * @param actions the action registry of the editor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorStateHarness(
    val testScope: TestScope,
    initialText: String,
    platform: HostPlatform = HostPlatform.MAC_OS,
    val services: FakeLanguageServices = FakeLanguageServices(),
    language: KarpfenLanguage = KarpfenLanguage.KSTATES,
    actions: EditorActionRegistry = EditorActionRegistry(),
) {
    /** The dispatcher of the session and of the editor's background work. It runs on the test scheduler. */
    val dispatcher = StandardTestDispatcher(testScope.testScheduler)

    /** The clock of the text's undo coalescing. */
    val time = TestTimeSource()

    /** The edited text. */
    val text: EditableText = EditableText(initialText, time)

    /** The keymap of [platform]. */
    val keymap: EditorKeymap = defaultEditorKeymap(platform)

    /** The scope of the session and the editor. */
    val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob())

    /** The document session. */
    val session: DocumentSession = DocumentSession(
        id = DocumentId.of(Path.of(System.getProperty("java.io.tmpdir"), "editor-test.${language.extension}")),
        support = defaultLanguageRegistry().support(language),
        text = text,
        scope = scope,
        config = DocumentSessionConfig(tokenizeDispatcher = dispatcher, parseDispatcher = dispatcher),
    )

    /** The clipboard. */
    val clipboard = TestClipboard()

    /** The display settings. */
    val settings = MutableStateFlow(EditorSettings())

    /** The editor state under test. */
    val state: CodeEditorState = CodeEditorState(
        session = session,
        languageServices = services,
        scope = scope,
        clipboard = clipboard,
        settings = settings,
        keymap = keymap,
        actions = actions,
        backgroundDispatcher = dispatcher,
    )

    /** Closes the editor and the session and cancels their scope. */
    fun close() {
        state.close()
        session.close()
        scope.cancel()
    }

    /** The plain text. */
    val plain: String get() = text.snapshot.value.text

    /** Reports a laid-out viewport: [visibleLines] lines of [lineHeight] px, scrolled to the top. */
    fun layOut(visibleLines: Int = 10, lineHeight: Float = LINE_HEIGHT) {
        val lines = text.snapshot.value.lineCount
        state.viewport.updateGeometry(
            viewportWidth = 800f,
            viewportHeight = visibleLines * lineHeight,
            lineHeight = lineHeight,
            contentWidth = 2000f,
            contentHeight = (lines + EditorViewport.CARET_MARGIN_LINES) * lineHeight,
        )
    }

    /**
     * Applies the pending scroll request like the view does, but only vertically, and consumes it. Returns the request,
     * or null if none was pending.
     */
    fun applyScrollRequest(): ScrollRequest? {
        val request = state.scrollRequest.value ?: return null
        val geometry = state.viewport.state.value
        val top = text.snapshot.value.lineIndex.lineOf(request.offset) * geometry.lineHeight
        state.viewport.reveal(0f, top, 1f, top + geometry.lineHeight, request.center)
        state.consumeScrollRequest(request)
        return request
    }

    /** The offset of the first occurrence of [marker]. The marker must occur in the text. */
    fun offsetOf(marker: String): Int = plain.indexOf(marker).also { require(it >= 0) { "no '$marker' in the text" } }

    /** The range of the first occurrence of [marker]. */
    fun rangeOf(marker: String): TextRange = TextRange.ofLength(offsetOf(marker), marker.length)

    /** Presses [key] with [modifiers] through the editor's key routing. */
    fun press(key: KeyCode, vararg modifiers: KeyModifier): Boolean = state.handleKey(KeyStroke(key, modifiers.toSet()))

    /** Holds the geometry that the harness reports. */
    companion object {
        /** The line height reported by [layOut]. */
        const val LINE_HEIGHT: Float = 20f
    }
}

/** A clipboard in memory. Setting [content] directly stands for another application and drops the whole-line mark. */
class TestClipboard(content: String? = null) : ClipboardPort {
    /** The clipboard text. */
    var content: String? = content
        set(value) {
            field = value
            wholeLine = false
        }

    /** Whether the content was put there as a whole line. */
    private var wholeLine = false

    override fun getText(): String? = content

    override fun setText(text: String, wholeLine: Boolean): Boolean {
        content = text
        this.wholeLine = wholeLine
        return true
    }

    override fun holdsWholeLine(): Boolean = wholeLine
}

/**
 * Runs [block] with an [EditorStateHarness] over [text] in `runTest` on virtual time, and closes the harness afterwards.
 */
fun editorTest(
    text: String,
    platform: HostPlatform = HostPlatform.MAC_OS,
    services: FakeLanguageServices = FakeLanguageServices(),
    block: suspend TestScope.(EditorStateHarness) -> Unit,
): TestResult = runTest {
    val harness = EditorStateHarness(this, text, platform, services)
    try {
        block(harness)
    } finally {
        harness.close()
    }
}
