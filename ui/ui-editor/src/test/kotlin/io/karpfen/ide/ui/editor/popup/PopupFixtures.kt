package io.karpfen.ide.ui.editor.popup

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.HighlightStyle
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.action.AutoClosedPairs
import io.karpfen.ide.ui.editor.model.action.ClipboardPort
import io.karpfen.ide.ui.editor.model.action.EditorAction
import io.karpfen.ide.ui.editor.model.action.EditorActionContext
import io.karpfen.ide.ui.editor.model.action.EditorActionExecutor
import io.karpfen.ide.ui.editor.model.action.EditorActionRegistry
import io.karpfen.ide.ui.editor.model.action.EditorUiPort
import io.karpfen.ide.ui.editor.model.action.typeTextAction
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import io.karpfen.ide.ui.editor.model.completion.CompletionSessionModel
import io.karpfen.ide.ui.editor.model.completion.CompletionSource
import io.karpfen.ide.ui.editor.model.find.FindModel
import io.karpfen.ide.ui.editor.model.navigation.GoToLineQuery
import io.karpfen.ide.ui.editor.overlay.EditorOverlayHost
import io.karpfen.ide.ui.editor.state.EditorOverlayState
import io.karpfen.ide.ui.editor.state.EditorPopupModel
import io.karpfen.ide.ui.editor.theme.EditorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.time.TestTimeSource

/**
 * The fake [EditorPopupModel] of the popup tests (the narrow interface, never `CodeEditorState`): the real
 * Compose-free models — [CaretModel], [FindModel], [CompletionSessionModel] — over a real [EditableText], with an
 * executor running their actions in a fake [EditorActionContext], and a real [EditorOverlayState]. It records what the
 * popups asked the editor for: [executed] action ids, [goToLineQueries] and [focusRequests].
 *
 * The models run on an unconfined test dispatcher (their scope and find's background dispatcher), so every model
 * operation has published its state when it returns; [close] cancels them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakePopupModel(initialText: String, caretAt: Int = 0) : EditorPopupModel, AutoCloseable {

    private val job = Job()
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher + job)

    /** The edited text. */
    val text: EditableText = EditableText(initialText, TestTimeSource())

    override val snapshot: StateFlow<TextSnapshot> get() = text.snapshot

    override val caret: CaretModel = CaretModel(text, scope)

    private val context = FakeActionContext(text, caret)
    private val registry = EditorActionRegistry()

    /** Ids of the actions the models ran through the executor (select match, replace, accept completion). */
    val executorLog: MutableList<String> = mutableListOf()

    private val executor = EditorActionExecutor { action: EditorAction ->
        executorLog += action.id
        action.isEnabled(context) && action.execute(context)
    }

    /** What the completion source answers for a snapshot and offset (default: nothing). */
    var completionItems: (TextSnapshot, Int) -> List<CompletionItem> = { _, _ -> emptyList() }

    override val find: FindModel = FindModel(text, caret, executor, scope, dispatcher)

    override val completion: CompletionSessionModel = CompletionSessionModel(
        text,
        caret,
        object : CompletionSource {
            override suspend fun items(snapshot: TextSnapshot, offset: Int): List<CompletionItem> = completionItems(snapshot, offset)
        },
        executor,
        scope,
    )

    override val overlays: EditorOverlayState = EditorOverlayState()

    /** Enabled action ids; every registered action by default, tests set it explicitly. */
    override val enabledActions: MutableStateFlow<Set<String>> = MutableStateFlow(registry.actions.map { it.id }.toSet())

    /** Ids passed to [execute], in order. */
    val executed: MutableList<String> = mutableListOf()

    /** Queries passed to [goToLine], in order. */
    val goToLineQueries: MutableList<GoToLineQuery> = mutableListOf()

    /** Number of [requestFocus] calls. */
    var focusRequests: Int = 0
        private set

    init {
        caret.moveTo(caretAt)
    }

    /** The plain text. */
    val plain: String get() = text.snapshot.value.text

    /** Types [typed] like the editor state: the typing action through the executor, then the completion trigger. */
    fun type(typed: String) {
        executor.execute(typeTextAction(typed))
        completion.onTyped(typed)
    }

    override fun execute(actionId: String): Boolean {
        executed += actionId
        return registry.execute(actionId, context)
    }

    /** Like the editor state: moves the caret (clamped), closes the popup and requests focus. */
    override fun goToLine(query: GoToLineQuery) {
        goToLineQueries += query
        caret.moveTo(query.offsetIn(text.snapshot.value))
        overlays.closeGoToLine()
        requestFocus()
    }

    override fun requestFocus() {
        focusRequests++
    }

    override fun close() = job.cancel()
}

/** An [EditorActionContext] over real text and carets: no tokens, an in-memory clipboard, UI requests ignored. */
class FakeActionContext(override val text: EditableText, override val caret: CaretModel) : EditorActionContext {
    override val autoPairs: AutoClosedPairs = AutoClosedPairs(text)
    override val tokens: TokenizeResult get() = TokenizeResult.empty(text.snapshot.value.version)
    override val pageLines: Int = 10
    override val clipboard: ClipboardPort = object : ClipboardPort {
        private var content: String? = null
        private var wholeLine = false

        override fun getText(): String? = content

        override fun setText(text: String, wholeLine: Boolean): Boolean {
            content = text
            this.wholeLine = wholeLine
            return true
        }

        override fun holdsWholeLine(): Boolean = wholeLine
    }
    override val ui: EditorUiPort = object : EditorUiPort {
        override fun openFind(replace: Boolean) = Unit
        override fun openGoToLine() = Unit
        override fun findNext() = Unit
        override fun findPrevious() = Unit
        override fun triggerCompletion() = Unit
        override fun goToDefinition() = Unit
    }
}

/** Fixed geometry of [FakeOverlayHost]: every character is [CHAR] × [LINE] px, line n starts at y = n × [LINE]. */
object TestGeometry {
    const val CHAR: Float = 8f
    const val LINE: Float = 16f

    /** The rectangle of the character at [line]/[column]. */
    fun cell(line: Int, column: Int): Rect = Rect(column * CHAR, line * LINE, (column + 1) * CHAR, (line + 1) * LINE)
}

/**
 * An [EditorOverlayHost] with fixed geometry ([TestGeometry]) over [state]: [caretBounds] is set by the test (default:
 * the caret's cell at creation); lines in [offscreenLines] are "not laid out" ([boundsOf] answers null).
 */
class FakeOverlayHost(
    override val state: FakePopupModel,
    override val theme: EditorTheme = testEditorTheme(),
    override val textStyle: TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
) : EditorOverlayHost {

    /** Lines treated as scrolled out of view. */
    var offscreenLines: Set<Int> = emptySet()

    override var caretBounds: Rect? by mutableStateOf(boundsOf(state.caret.primary.position))

    override fun boundsOf(offset: Int): Rect? {
        val snapshot = state.snapshot.value
        if (offset !in 0..snapshot.length) return null
        val position = snapshot.positionOf(offset)
        if (position.line in offscreenLines) return null
        return TestGeometry.cell(position.line, position.column)
    }
}

/** An editor theme for tests (the real palettes are WP-EDITOR-VIEW's). */
fun testEditorTheme(): EditorTheme = EditorTheme(
    background = Color(0xFFFFFFFF),
    foreground = Color(0xFF202020),
    gutterBackground = Color(0xFFF0F0F0),
    gutterForeground = Color(0xFF909090),
    currentLine = Color(0xFFF5F5F5),
    selection = Color(0xFFB0D0FF),
    caret = Color(0xFF000000),
    matchingBracket = Color(0xFFD0D0D0),
    findMatch = Color(0xFFFFF0A0),
    findMatchCurrent = Color(0xFFFFC040),
    error = Color(0xFFD00000),
    warning = Color(0xFFC08000),
    info = Color(0xFF0060C0),
    popupBackground = Color(0xFFFAFAFA),
    popupBorder = Color(0xFFC0C0C0),
    popupSelection = Color(0xFFD8E8FF),
    styles = mapOf(
        HighlightStyle.KEYWORD to SpanStyle(color = Color(0xFF0000A0)),
        HighlightStyle.TYPE_NAME to SpanStyle(color = Color(0xFF008080)),
    ),
)

/** Test tag of the text area stand-in that hosts the popup slot. */
const val TEXT_AREA: String = "test.textArea"

/**
 * Composes [popups] like `CodeEditor` hosts them: the header slot above a focusable text-area stand-in ([TEXT_AREA],
 * [width] × the rest of [height]) whose Box holds the popup slot. Nothing else is in the window. Returns the text
 * area's focus requester (to move focus away from a popup).
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.setPopups(
    host: EditorOverlayHost,
    popups: EditorPopups = EditorPopups(),
    width: Dp = 800.dp,
    height: Dp = 480.dp,
): FocusRequester {
    val focus = FocusRequester()
    setContent {
        Column(Modifier.size(width, height)) {
            popups.Header(host)
            Box(Modifier.fillMaxWidth().weight(1f).testTag(TEXT_AREA).focusRequester(focus).focusable()) {
                popups.Popups(host)
            }
        }
    }
    return focus
}

/** What a popup UI test works with: the fake model, the fake host and the text area's focus requester. */
class PopupHarness(val model: FakePopupModel, val host: FakeOverlayHost, val textArea: FocusRequester)

/**
 * Runs a headless UI test of [popups] over a [FakePopupModel] of [text] (caret at [caretAt]) composed by [setPopups];
 * the model is closed afterwards.
 */
@OptIn(ExperimentalTestApi::class)
fun popupTest(
    text: String,
    caretAt: Int = 0,
    popups: EditorPopups = EditorPopups(),
    block: ComposeUiTest.(PopupHarness) -> Unit,
) = runComposeUiTest {
    FakePopupModel(text, caretAt).use { model ->
        val host = FakeOverlayHost(model)
        val textArea = setPopups(host, popups)
        block(PopupHarness(model, host, textArea))
    }
}

/** The bounds of the node tagged [tag] relative to the text area stand-in ([TEXT_AREA]), in dp. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.boundsInTextArea(tag: String): DpRect {
    val area = onNodeWithTag(TEXT_AREA).getUnclippedBoundsInRoot()
    val node = onNodeWithTag(tag).getUnclippedBoundsInRoot()
    return DpRect(node.left - area.left, node.top - area.top, node.right - area.left, node.bottom - area.top)
}

/** Completion items for [labels] (kind [kind], detail "detail of <label>"), replacing the identifier before the offset. */
fun completionItems(labels: List<String>, kind: CompletionKind = CompletionKind.KEYWORD): (TextSnapshot, Int) -> List<CompletionItem> =
    { snapshot, offset ->
        var start = offset
        while (start > 0 && (snapshot[start - 1].isLetterOrDigit() || snapshot[start - 1] == '_')) start--
        labels.mapIndexed { index, label ->
            CompletionItem(label, kind, label, TextRange(start, offset), null, "detail of $label", null, "%04d".format(index))
        }
    }

/** Matches completion rows (any index). */
val isCompletionRow: SemanticsMatcher = SemanticsMatcher("is a completion row") { node ->
    node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(EditorPopupTags.completionRow(0).removeSuffix("0")) == true
}
