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
package io.karpfen.ide.ui.editor.model.completion

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.action.CommandEditAction
import io.karpfen.ide.ui.editor.model.action.EditorActionExecutor
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Why completion opened. */
public enum class CompletionTrigger {
    /** An explicit request, through the completion key Ctrl+M or the Complete Code command. */
    EXPLICIT,

    /** An identifier character typed after at least one identifier character. */
    IDENTIFIER,

    /** `"` typed in a completable string position. */
    QUOTE,

    /** `$(` typed, which starts a path reference in EVAL code. */
    PATH_START,

    /** `->` typed, which starts the next path segment. */
    ARROW,
}

/**
 * Supplies completion items before filtering. The editor state implements it with `LanguageServices.complete` on
 * `LanguageDocument.of(session, snapshot)`, which tokenizes the snapshot if the tokens of the session lag behind.
 *
 * The function suspends, and the implementation decides where the work runs. The editor state switches to its
 * injected background dispatcher with `withContext`. So the dispatcher is injected in one place, and the model never
 * names one. Implementations are safe to call from the main thread, can be cancelled and must not throw.
 */
public fun interface CompletionSource {
    /** Returns the items at [offset] of [snapshot]. Their `replaceRange` values are offsets of [snapshot]. */
    public suspend fun items(snapshot: TextSnapshot, offset: Int): List<CompletionItem>
}

/**
 * The visible state of an open completion session.
 *
 * @property items the filtered and sorted items. The list is never empty while the session is open.
 * @property selectedIndex the index of the highlighted item.
 * @property replaceRange the range that accepting replaces. It is the replace range of the items, extended to the caret.
 * @property filterText the text between `replaceRange.start` and the caret, which filters the items.
 */
public data class CompletionUiState(
    public val items: List<CompletionItem>,
    public val selectedIndex: Int,
    public val replaceRange: TextRange,
    public val filterText: String,
) {
    init {
        require(items.isNotEmpty()) { "an open completion session shows at least one item" }
        require(selectedIndex in items.indices) { "selectedIndex $selectedIndex out of range" }
    }

    /** The highlighted item. */
    public val selected: CompletionItem get() = items[selectedIndex]

    /** Constants of [CompletionUiState]. */
    public companion object {
        /**
         * The most rows the completion list shows at once. It sets the height of the popup and the number of rows that
         * Page Up and Page Down move the selection by, so keyboard paging matches what the user sees.
         */
        public const val MAX_VISIBLE_ROWS: Int = 12
    }
}

/**
 * The completion session of one editor. It is a state holder without Compose, used from the UI thread.
 *
 * Completion opens explicitly through [open], or it triggers automatically from [onTyped]. The automatic triggers are
 * an identifier character typed after at least one identifier character, a `"` in a string position that can be
 * completed, `$(` and `->`. If the source returns no items, completion stays closed.
 *
 * The model asks the [CompletionSource] for items of the snapshot that is current at the request. It does so in a
 * coroutine launched in its scope, and the source switches threads itself. A newer request cancels an older one.
 * Results computed for an older version are dropped, so the `replaceRange` values always match the text they are
 * applied to. A dropped request is repeated for the current text only while the caret is still in the word it
 * completes. That is the case when the text changed elsewhere, or when identifier characters were typed or deleted in
 * the word. After a line break, a space or other punctuation it is not the case, so completion never opens where no
 * trigger fired. Tests use a test dispatcher in the scope and a fake source, so nothing runs on real threads.
 *
 * The items are filtered by the text between `replaceRange.start` and the caret, ignoring case. Prefix matches come
 * first, then word boundary and camel hump matches, then subsequence matches. The sort is stable and orders by match
 * quality, then by `sortKey`, then by label.
 *
 * [accept] inserts `insertText` over the replace range as one `EditorAction`. The action runs through the
 * [EditorActionExecutor] passed to the factory, so it makes one undo step with caret mementos. The caret goes to
 * `caretOffsetInInsert`.
 *
 * The model follows `CaretModel.state` itself. It closes when the primary caret leaves the replace range or the word
 * being completed, which means that something other than identifier characters was typed since the request. An example
 * is the space and the `{` after `DO`. It also closes when no item matches, on Escape and on focus loss through
 * [close].
 */
public interface CompletionSessionModel {

    /** The open session, or null when closed. */
    public val state: StateFlow<CompletionUiState?>

    /** Opens completion at the primary caret, or opens it again if it is already open. */
    public fun open(trigger: CompletionTrigger = CompletionTrigger.EXPLICIT)

    /** Tells the model that [text] was just typed at the caret. The model then checks the auto-triggers and filters again. */
    public fun onTyped(text: String)

    /**
     * Highlights the item [delta] rows away, clamped to the list. The arrow keys move by one row, and Page Up and Page
     * Down move by [CompletionUiState.MAX_VISIBLE_ROWS].
     */
    public fun moveSelection(delta: Int)

    /** Highlights the item at [index]. Mouse hover and click use it. */
    public fun select(index: Int)

    /** Accepts the highlighted item. Returns false if completion is closed. */
    public fun accept(): Boolean

    /** Closes the session. */
    public fun close()

    /**
     * Handles keys while completion is open. Up, Down, Page Up and Page Down move the selection. Enter and Tab accept,
     * and Escape closes. Returns true if the key was consumed, and then the editor does not run its own binding. Always
     * returns false while completion is closed.
     */
    public fun handleKey(stroke: KeyStroke): Boolean
}

/**
 * Creates a [CompletionSessionModel] for [text] and [caret]. It asks [source] for items and accepts through
 * [executor]. Caret observation and item requests run in [scope], which is the UI scope of the editor. [source] moves
 * the work off the UI thread.
 */
public fun CompletionSessionModel(
    text: EditableText,
    caret: CaretModel,
    source: CompletionSource,
    executor: EditorActionExecutor,
    scope: CoroutineScope,
): CompletionSessionModel = DefaultCompletionSessionModel(text, caret, source, executor, scope)

/**
 * The default [CompletionSessionModel]. It is a coordinator. [CompletionTriggers] decides the auto-triggers.
 * [CompletionSession] filters an open session for the current text and caret with [CompletionRanking], and it computes
 * what accepting does. `CommandEditAction` carries the accepted edit to the executor. This class owns the request and
 * the current session.
 *
 * A request runs in [scope]. Its result is installed only for the version it was computed for. Otherwise it is
 * dropped, and the request is repeated for the current text while it still holds there
 * ([CompletionTriggers.stillHolds]). The caret is observed in [scope] as well, so [state] may lag a caret change by one
 * dispatch. That is why [accept] and [handleKey] first check the session against the current caret again. They never
 * act on a session the caret has already left.
 */
internal class DefaultCompletionSessionModel(
    private val text: EditableText,
    private val caret: CaretModel,
    private val source: CompletionSource,
    private val executor: EditorActionExecutor,
    private val scope: CoroutineScope,
) : CompletionSessionModel {

    private val uiState = MutableStateFlow<CompletionUiState?>(null)
    private var session: CompletionSession? = null
    private var request: Job? = null

    init {
        scope.launch { caret.state.collect { refresh() } }
    }

    override val state: StateFlow<CompletionUiState?> = uiState.asStateFlow()

    override fun open(trigger: CompletionTrigger) = request(trigger)

    override fun onTyped(text: String) {
        if (session != null) refresh()
        val trigger = CompletionTriggers.afterTyping(this.text.snapshot.value, caret.primary.position, text) ?: return
        if (session == null || trigger != CompletionTrigger.IDENTIFIER) request(trigger)
    }

    override fun moveSelection(delta: Int) {
        val current = uiState.value ?: return
        select((current.selectedIndex + delta).coerceIn(0, current.items.lastIndex))
    }

    override fun select(index: Int) {
        val current = uiState.value ?: return
        if (index in current.items.indices) uiState.value = current.copy(selectedIndex = index)
    }

    override fun accept(): Boolean {
        refresh()
        val item = uiState.value?.selected ?: return false
        val open = session ?: return false
        val version = text.snapshot.value.version
        val acceptance = open.acceptance(text, caret.primary.position, item)
        close()
        val (edit, caretAfter) = acceptance ?: return false
        val action = CommandEditAction(ACCEPT_ID, "Accept Completion", version, listOf(edit), listOf(CaretMemento.at(caretAfter)))
        return executor.execute(action)
    }

    override fun close() {
        request?.cancel()
        request = null
        dismiss()
    }

    override fun handleKey(stroke: KeyStroke): Boolean {
        refresh()
        val open = uiState.value != null
        if (stroke.modifiers.isNotEmpty()) return false
        when (stroke.key) {
            KeyCode.ESCAPE -> close()
            KeyCode.UP -> if (open) moveSelection(-1)
            KeyCode.DOWN -> if (open) moveSelection(1)
            KeyCode.PAGE_UP -> if (open) moveSelection(-CompletionUiState.MAX_VISIBLE_ROWS)
            KeyCode.PAGE_DOWN -> if (open) moveSelection(CompletionUiState.MAX_VISIBLE_ROWS)
            KeyCode.ENTER, KeyCode.TAB -> if (open) accept()
            else -> return false
        }
        return open
    }

    /**
     * Asks the source for the items at the primary caret of the current text. A newer request cancels this one. A
     * failing source is logged and yields no items.
     *
     * The job starts only after it was assigned to [request]. An eager dispatcher may complete the request at once and
     * start a newer one from [receive]. Starting late makes sure that no finished job is left in [request] then.
     */
    private fun request(trigger: CompletionTrigger) {
        request?.cancel()
        val snapshot = text.snapshot.value
        val offset = caret.primary.position
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val items = try {
                source.items(snapshot, offset)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("completion source failed at offset {} of version {}", offset, snapshot.version, e)
                emptyList()
            }
            ensureActive()
            request = null
            receive(trigger, snapshot, offset, items)
        }
        request = job
        job.start()
    }

    /**
     * Installs [items], which were computed for [snapshot] at [offset]. Results for an older version are dropped. They
     * are requested again at the caret while the request still holds there.
     */
    private fun receive(trigger: CompletionTrigger, snapshot: TextSnapshot, offset: Int, items: List<CompletionItem>) {
        if (snapshot.version != text.snapshot.value.version) {
            if (CompletionTriggers.stillHolds(text, snapshot.version, offset, caret.primary)) request(trigger)
            return
        }
        if (items.isEmpty()) {
            dismiss()
            return
        }
        session = CompletionSession.of(items, snapshot, offset)
        uiState.value = null
        refresh()
    }

    /**
     * Filters the open session again for the current text and caret. It closes the session when the caret is outside
     * of it or nothing matches.
     */
    private fun refresh() {
        val open = session ?: return
        val next = open.uiState(text, caret.primary, uiState.value)
        if (next == null) dismiss() else uiState.value = next
    }

    /** Closes the visible session. A running request is kept, because it may open a new session. */
    private fun dismiss() {
        session = null
        uiState.value = null
    }

    /** The id of the accept edits and the logger. */
    private companion object {
        /** The id of the internal action that applies an accepted item. It is neither registered nor bound to a key. */
        const val ACCEPT_ID = "editor.completion.accept"

        /** Reports sources that failed. A failed source yields no items. */
        val logger: Logger = LoggerFactory.getLogger(DefaultCompletionSessionModel::class.java)
    }
}
