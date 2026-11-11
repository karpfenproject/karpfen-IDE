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
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
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
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * The fake [EditorPopupModel] of the popup tests. It implements the narrow interface and never `CodeEditorState`. It
 * uses the real models [CaretModel], [FindModel] and [CompletionSessionModel], which need no Compose, over a real
 * [EditableText]. An executor runs their actions in a fake [EditorActionContext], and the [EditorOverlayState] is real.
 * The fake records what the popups asked the editor for: [executed] action ids, [goToLineQueries], [focusRequests] and
 * [tooltipPointer].
 *
 * The models run on an unconfined test dispatcher, which serves as their scope and as the background dispatcher of
 * find. So every model operation has published its state when it returns. [close] cancels the models.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakePopupModel(initialText: String, caretAt: Int = 0) : EditorPopupModel, AutoCloseable {

    /** The job of the models' scope. */
    private val job = Job()

    /** The unconfined dispatcher of the models and of the background work of find. */
    private val dispatcher = UnconfinedTestDispatcher()

    /** The scope of the models. */
    private val scope = CoroutineScope(dispatcher + job)

    /** The edited text. */
    val text: EditableText = EditableText(initialText, TestTimeSource())

    override val snapshot: StateFlow<TextSnapshot> get() = text.snapshot

    override val caret: CaretModel = CaretModel(text, scope)

    /** The context in which [executor] runs actions. */
    private val context = FakeActionContext(text, caret)

    /** The default actions, used for the initial [enabledActions]. */
    private val registry = EditorActionRegistry()

    /** Ids of the actions that the models ran through the executor, such as select match, replace and accept completion. */
    val executorLog: MutableList<String> = mutableListOf()

    /** Logs each action in [executorLog] and runs it in [context] while it is enabled. */
    private val executor = EditorActionExecutor { action: EditorAction ->
        executorLog += action.id
        action.isEnabled(context) && action.execute(context)
    }

    /** What the completion source answers for a snapshot and offset. By default it answers nothing. */
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

    /** The enabled action ids. By default every registered action is enabled, and tests set the value explicitly. */
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

    /** Types [typed] like the editor state does. It runs the typing action through the executor and then the completion trigger. */
    fun type(typed: String) {
        executor.execute(typeTextAction(typed))
        completion.onTyped(typed)
    }

    override fun execute(actionId: String): Boolean {
        executed += actionId
        return registry.execute(actionId, context)
    }

    /** Acts like the editor state. Moves the caret, clamped to the text, closes the popup and requests focus. */
    override fun goToLine(query: GoToLineQuery) {
        goToLineQueries += query
        caret.moveTo(query.offsetIn(text.snapshot.value))
        overlays.closeGoToLine()
        requestFocus()
    }

    override fun requestFocus() {
        focusRequests++
    }

    /** What [pointerOverTooltip] reported, in order. */
    val tooltipPointer: MutableList<Boolean> = mutableListOf()

    override fun pointerOverTooltip(over: Boolean) {
        tooltipPointer += over
    }

    override fun close() = job.cancel()
}

/** An [EditorActionContext] over real text and carets. It has no tokens and an in-memory clipboard, and it ignores UI requests. */
class FakeActionContext(override val text: EditableText, override val caret: CaretModel) : EditorActionContext {
    override val autoPairs: AutoClosedPairs = AutoClosedPairs(text)
    override val tokens: TokenizeResult get() = TokenizeResult.empty(text.snapshot.value.version)
    override val pageLines: Int = 10
    override val lineComment: String = KarpfenSyntax.LINE_COMMENT
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

/**
 * The fixed geometry of [FakeOverlayHost]. Every character cell is [CHAR] px wide and [LINE] px high, and line n starts
 * at y = n * [LINE].
 */
object TestGeometry {
    /** Width of one character cell. */
    const val CHAR: Float = 8f

    /** Height of one line. */
    const val LINE: Float = 16f

    /** The rectangle of the character at [line] and [column]. */
    fun cell(line: Int, column: Int): Rect = Rect(column * CHAR, line * LINE, (column + 1) * CHAR, (line + 1) * LINE)
}

/**
 * An [EditorOverlayHost] over [state] with the fixed geometry of [TestGeometry]. The test sets [caretBounds], which
 * starts as the cell of the caret at creation, and [revealPending]. Lines in [offscreenLines] count as not laid out, so
 * [boundsOf] answers null for them.
 */
class FakeOverlayHost(
    override val state: FakePopupModel,
    override val theme: EditorTheme = testEditorTheme(),
    override val textStyle: TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
) : EditorOverlayHost {

    /** Lines treated as scrolled out of view. */
    var offscreenLines: Set<Int> = emptySet()

    override var caretBounds: Rect? by mutableStateOf(boundsOf(state.caret.primary.position))

    /** True while the view is about to reveal the caret because a scroll request is pending. */
    var revealPending: Boolean by mutableStateOf(false)

    override val isCaretScrolledAway: Boolean get() = caretBounds == null && !revealPending

    override fun boundsOf(offset: Int): Rect? {
        val snapshot = state.snapshot.value
        if (offset !in 0..snapshot.length) return null
        val position = snapshot.positionOf(offset)
        if (position.line in offscreenLines) return null
        return TestGeometry.cell(position.line, position.column)
    }
}

/** An editor theme for tests, independent of the editor's real palettes. */
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
 * Composes [popups] like `CodeEditor` hosts them. The header slot lies above a focusable stand-in for the text area,
 * tagged [TEXT_AREA], which is [width] wide and fills the rest of [height]. Its box holds the popup slot. Nothing else
 * is in the window. Returns the focus requester of the text area, which tests use to move focus away from a popup.
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
 * Runs a headless UI test of [popups] over a [FakePopupModel] of [text] with the caret at [caretAt], composed by
 * [setPopups]. The model is closed afterwards.
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

/** The bounds in dp of the node tagged [tag], relative to the stand-in for the text area tagged [TEXT_AREA]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.boundsInTextArea(tag: String): DpRect {
    val area = onNodeWithTag(TEXT_AREA).getUnclippedBoundsInRoot()
    val node = onNodeWithTag(tag).getUnclippedBoundsInRoot()
    return DpRect(node.left - area.left, node.top - area.top, node.right - area.left, node.bottom - area.top)
}

/**
 * Completion items for [labels] of kind [kind], each with the detail "detail of" followed by its label. Each item
 * replaces the identifier before the offset, and the sort keys keep the order of [labels].
 */
fun completionItems(labels: List<String>, kind: CompletionKind = CompletionKind.KEYWORD): (TextSnapshot, Int) -> List<CompletionItem> =
    { snapshot, offset ->
        var start = offset
        while (start > 0 && (snapshot[start - 1].isLetterOrDigit() || snapshot[start - 1] == '_')) start--
        labels.mapIndexed { index, label ->
            CompletionItem(label, kind, label, TextRange(start, offset), null, "detail of $label", null, "%04d".format(index))
        }
    }

/** Matches completion rows at any index. */
val isCompletionRow: SemanticsMatcher = SemanticsMatcher("is a completion row") { node ->
    node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(EditorPopupTags.completionRow(0).removeSuffix("0")) == true
}
