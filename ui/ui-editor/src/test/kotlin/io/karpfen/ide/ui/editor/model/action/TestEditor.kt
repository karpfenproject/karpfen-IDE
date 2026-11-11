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
package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.caret.Caret
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.time.TestTimeSource

/**
 * An editor for model tests. It uses the real [EditableText], [CaretModel] and [AutoClosedPairs] with a fake clipboard,
 * a recording UI port and hand-built tokens. Texts are written with markers. `|` is the caret and `^` is the anchor of
 * a selection. Neither marker is part of the text itself.
 *
 * @param marked the initial text in the [Marked] notation.
 * @param scope the scope in which the caret model follows the text. It is unconfined by default, so the tests need no
 *   virtual time.
 * @param tabWidth the tab width the caret model uses for display columns.
 * @param tokensOf the tokens of a snapshot. By default there are none, so there are no EVAL bodies.
 * @param lineComment the line comment of the edited language. By default it is the karpfen one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestEditor(
    marked: String,
    scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher() + Job()),
    tabWidth: Int = 4,
    private val tokensOf: (TextSnapshot) -> TokenizeResult = { TokenizeResult.empty(it.version) },
    override val lineComment: String? = KarpfenSyntax.LINE_COMMENT,
) : EditorActionContext {

    /** The clock of the text's undo coalescing. */
    val time = TestTimeSource()

    private val initial = Marked.parse(marked)

    override val text: EditableText = EditableText(initial.text, time)

    override val caret: CaretModel = CaretModel(text, scope) { tabWidth }

    override val autoPairs: AutoClosedPairs = AutoClosedPairs(text)

    /** How often [tokens] was read. Actions read tokens only when they need them, and tests check that with this count. */
    var tokenReads: Int = 0
        private set

    override val tokens: TokenizeResult
        get() {
            tokenReads++
            return tokensOf(text.snapshot.value)
        }

    override var pageLines: Int = 10

    override val clipboard: FakeClipboard = FakeClipboard()

    override val ui: RecordingUiPort = RecordingUiPort()

    /** The default actions. */
    val registry: EditorActionRegistry = EditorActionRegistry()

    /** Runs actions in this context like the editor state does, which means only while they are enabled. */
    val executor: EditorActionExecutor = EditorActionExecutor { action -> action.isEnabled(this) && action.execute(this) }

    init {
        caret.restore(listOf(initial.caret.toMemento()))
    }

    /** Runs the registered action [id]. */
    fun run(id: String): Boolean = registry.execute(id, this)

    /** Types [typed] through [typeTextAction]. */
    fun type(typed: String): Boolean = executor.execute(typeTextAction(typed))

    /** Types every character of [typed] separately, like key presses. */
    fun typeEach(typed: String) = typed.forEach { type(it.toString()) }

    /** The text with the caret marker, and with the anchor marker when there is a selection. */
    fun marked(): String = Marked.render(text.snapshot.value.text, caret.primary)

    /** The plain text. */
    val plain: String get() = text.snapshot.value.text
}

/** The notation of [TestEditor], where `|` marks the caret and `^` marks the anchor of a selection. */
object Marked {
    /** A text with its caret. */
    data class Parsed(val text: String, val caret: Caret)

    /** Splits [marked] into text and caret. Without `|` the caret is at 0, and without `^` nothing is selected. */
    fun parse(marked: String): Parsed {
        val text = StringBuilder()
        var position = -1
        var anchor = -1
        for (char in marked) {
            when (char) {
                '|' -> position = text.length
                '^' -> anchor = text.length
                else -> text.append(char)
            }
        }
        if (position < 0) position = 0
        if (anchor < 0) anchor = position
        return Parsed(text.toString(), Caret(anchor, position))
    }

    /** [text] with the markers of [caret]. */
    fun render(text: String, caret: Caret): String {
        val marks = buildList {
            add(caret.position to '|')
            if (caret.hasSelection) add(caret.anchor to '^')
        }.sortedByDescending { it.first }
        val out = StringBuilder(text)
        marks.forEach { (offset, mark) -> out.insert(offset, mark) }
        return out.toString()
    }
}

/**
 * A clipboard in memory. Setting [content] directly stands for another application and drops the whole-line mark.
 * While [failing] is set, writes fail like on a system clipboard that another application holds.
 */
class FakeClipboard(content: String? = null) : ClipboardPort {
    /** The clipboard text. */
    var content: String? = content
        set(value) {
            field = value
            wholeLine = false
        }

    /** Whether the content was put there as a whole line. */
    var wholeLine: Boolean = false
        private set

    /** Makes every write fail. */
    var failing: Boolean = false

    override fun getText(): String? = content

    override fun setText(text: String, wholeLine: Boolean): Boolean {
        if (failing) return false
        content = text
        this.wholeLine = wholeLine
        return true
    }

    override fun holdsWholeLine(): Boolean = wholeLine
}

/** Records the UI requests of actions. */
class RecordingUiPort : EditorUiPort {
    /** The requests in order, by name. */
    val requests: MutableList<String> = mutableListOf()

    override fun openFind(replace: Boolean) {
        requests += if (replace) "openReplace" else "openFind"
    }

    override fun openGoToLine() {
        requests += "openGoToLine"
    }

    override fun findNext() {
        requests += "findNext"
    }

    override fun findPrevious() {
        requests += "findPrevious"
    }

    override fun triggerCompletion() {
        requests += "triggerCompletion"
    }

    override fun goToDefinition() {
        requests += "goToDefinition"
    }
}

/**
 * The tokens of a KStates test text from the registry's KStates tokenizer. Its embedded regions are the EVAL bodies
 * found by the shared scanner `EvalBodies`. A body opens only at an `EVAL {` that is code, so a commented-out
 * `// EVAL {` opens none, just as in the editor.
 */
fun evalBodies(snapshot: TextSnapshot): TokenizeResult = KSTATES_TOKENIZER.tokenize(snapshot)

/** The KStates tokenizer of the default language registry, created once for all tests. */
private val KSTATES_TOKENIZER = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES).tokenizer
