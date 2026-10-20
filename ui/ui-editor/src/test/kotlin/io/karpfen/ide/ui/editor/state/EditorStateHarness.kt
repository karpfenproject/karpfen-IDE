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
 * An editor state for Compose-free tests, wired like the product: a real [EditableText] (test time source), a real
 * [DocumentSession] with the language registry's tokenizer and parser, the real models, a [FakeLanguageServices] and an
 * in-memory clipboard. Every coroutine — the session's pipelines, the editor's scope and its background dispatcher —
 * runs on [testScope]'s scheduler in [scope] (foreground work, so `advanceUntilIdle` settles everything; the test
 * scope's `backgroundScope` would not be advanced by it). [close] cancels the scope; [editorTest] does so.
 *
 * The view is simulated with [layOut], which reports a viewport like `CodeEditor` does.
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
    /** The background dispatcher of the session and the editor (the test scheduler). */
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

    /** Applies the pending scroll request like the view does (vertical only) and consumes it. */
    fun applyScrollRequest(): ScrollRequest? {
        val request = state.scrollRequest.value ?: return null
        val geometry = state.viewport.state.value
        val top = text.snapshot.value.lineIndex.lineOf(request.offset) * geometry.lineHeight
        state.viewport.reveal(0f, top, 1f, top + geometry.lineHeight, request.center)
        state.consumeScrollRequest(request)
        return request
    }

    /** The offset of the first occurrence of [marker] (must exist). */
    fun offsetOf(marker: String): Int = plain.indexOf(marker).also { require(it >= 0) { "no '$marker' in the text" } }

    /** The range of the first occurrence of [marker]. */
    fun rangeOf(marker: String): TextRange = TextRange.ofLength(offsetOf(marker), marker.length)

    /** Presses [key] with [modifiers] through the editor's key routing. */
    fun press(key: KeyCode, vararg modifiers: KeyModifier): Boolean = state.handleKey(KeyStroke(key, modifiers.toSet()))

    companion object {
        /** The line height reported by [layOut]. */
        const val LINE_HEIGHT: Float = 20f
    }
}

/** A clipboard in memory. */
class TestClipboard(var content: String? = null) : ClipboardPort {
    override fun getText(): String? = content

    override fun setText(text: String) {
        content = text
    }
}

/**
 * Runs [block] with an [EditorStateHarness] over [text] in `runTest` (virtual time) and closes the harness afterwards.
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
