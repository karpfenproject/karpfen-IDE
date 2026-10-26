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
    /** Explicit request: the completion key (Ctrl+M) or the Complete Code command. */
    EXPLICIT,

    /** An identifier character typed after at least one identifier character. */
    IDENTIFIER,

    /** `"` typed in a completable string position. */
    QUOTE,

    /** `$(` typed (EVAL path reference). */
    PATH_START,

    /** `->` typed (next path segment). */
    ARROW,
}

/**
 * Supplies unfiltered completion items; the editor state implements it with `LanguageServices.complete` on
 * `LanguageDocument.of(session, snapshot)` (tokenizing the snapshot if the session's tokens lag behind). Suspends: the
 * implementation decides where the work runs (the editor state's injected background dispatcher, via `withContext`),
 * so the dispatcher stays injected in one place and the model never names one. Main-safe, cancellable, must not throw.
 */
public fun interface CompletionSource {
    /** The items at [offset] of [snapshot]; their `replaceRange`s are in [snapshot]'s coordinates. */
    public suspend fun items(snapshot: TextSnapshot, offset: Int): List<CompletionItem>
}

/**
 * The visible state of an open completion session.
 *
 * @property items the filtered, sorted items (never empty while open).
 * @property selectedIndex index of the highlighted item.
 * @property replaceRange the range accepting will replace (the items' replace range, extended to the caret).
 * @property filterText text between `replaceRange.start` and the caret, used for filtering.
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
         * The most rows the completion list shows at once: the popup's height, and the rows Page Up/Down move the
         * selection by, so keyboard paging matches what the user sees.
         */
        public const val MAX_VISIBLE_ROWS: Int = 12
    }
}

/**
 * One editor's completion session. Compose-free state holder used from the UI thread.
 *
 * - Opens on [open] (explicit) or auto-triggers from [onTyped]: an identifier character typed after ≥ 1 identifier
 *   character, `"` in a completable string position, `$(` or `->`. Opening with no items keeps it closed.
 * - Asks the [CompletionSource] for the snapshot current at the request in a coroutine launched in its scope (the
 *   source switches threads itself; a newer request cancels an older one) and drops results computed for an older
 *   version, so `replaceRange`s always match the text they are applied to. A dropped request is repeated for the
 *   current text only while the caret is still in the word it completes (the text changed elsewhere, or identifier
 *   characters were typed or deleted there); after a line break, a space or other punctuation it is not, so completion
 *   never opens where no trigger fired. Tests use a test dispatcher in the scope and a fake source; nothing runs on
 *   real threads.
 * - Filters by the text between `replaceRange.start` and the caret, case-insensitively: prefix matches before
 *   camel/word-boundary matches before subsequence matches; stable sort by match quality, then sortKey, then label.
 * - [accept] inserts `insertText` over the replace range as one `EditorAction` run through the factory's
 *   [EditorActionExecutor] (one undo step, caret mementos) and places the caret at `caretOffsetInInsert`.
 * - Follows `CaretModel.state` itself: closes when the primary caret leaves the replace range or the word being
 *   completed (anything but identifier characters typed since the request, e.g. the space and `{` after `DO`), when no
 *   item matches, on Escape and on focus loss ([close]).
 */
public interface CompletionSessionModel {

    /** The open session, or null when closed. */
    public val state: StateFlow<CompletionUiState?>

    /** Opens (or re-opens) completion at the primary caret. */
    public fun open(trigger: CompletionTrigger = CompletionTrigger.EXPLICIT)

    /** Informs the model that [text] was just typed at the caret (auto-trigger and re-filtering). */
    public fun onTyped(text: String)

    /** Highlights the item [delta] rows away (clamped; ±1 for arrows, ±page for Page Up/Down). */
    public fun moveSelection(delta: Int)

    /** Highlights the item at [index] (mouse hover/click). */
    public fun select(index: Int)

    /** Accepts the highlighted item; returns false if closed. */
    public fun accept(): Boolean

    /** Closes the session. */
    public fun close()

    /**
     * Handles keys while open: Up/Down/Page Up/Page Down move the selection, Enter/Tab accept, Escape closes. Returns
     * true if the key was consumed (the editor then does not run its own binding); always false when closed.
     */
    public fun handleKey(stroke: KeyStroke): Boolean
}

/**
 * A [CompletionSessionModel] for [text]/[caret], asking [source] for items and accepting through [executor]; caret
 * observation and the item requests run in [scope] (the editor's UI scope; [source] moves the work off the UI thread).
 */
public fun CompletionSessionModel(
    text: EditableText,
    caret: CaretModel,
    source: CompletionSource,
    executor: EditorActionExecutor,
    scope: CoroutineScope,
): CompletionSessionModel = DefaultCompletionSessionModel(text, caret, source, executor, scope)

/**
 * Default [CompletionSessionModel], a coordinator: [CompletionTriggers] decides auto-triggers, [CompletionSession]
 * filters an open session for the current text and caret (with [CompletionRanking]) and computes what accepting does,
 * `CommandEditAction` carries the accepted edit to the executor; this class owns the request and the current session.
 * A request runs in [scope]; its result is installed only for the version it was computed for, otherwise it is dropped
 * and the request repeated for the current text while it still holds there ([CompletionTriggers.stillHolds]). The caret
 * is observed in [scope] as well, so [state] may lag a caret change by one dispatch; [accept] and [handleKey] therefore
 * re-check the session against the current caret first and never act on a session the caret has already left.
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
     * Asks the source for the items at the primary caret of the current text; a newer request cancels this one. The job
     * starts only after it was assigned to [request], so an eager dispatcher that completes the request at once (and
     * may start a newer one from [receive]) never leaves a finished job in [request].
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
     * Installs [items] computed for [snapshot] at [offset]; results for an older version are dropped, and re-requested
     * at the caret while the request still holds there.
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

    /** Re-filters the open session for the current text and caret, or closes it (caret outside, nothing matches). */
    private fun refresh() {
        val open = session ?: return
        val next = open.uiState(text, caret.primary, uiState.value)
        if (next == null) dismiss() else uiState.value = next
    }

    /** Closes the visible session; a running request stays (it may open a new session). */
    private fun dismiss() {
        session = null
        uiState.value = null
    }

    /** The id of the accept edits and the logger. */
    private companion object {
        /** The id of the internal action that applies an accepted item (not registered, not key-bound). */
        const val ACCEPT_ID = "editor.completion.accept"

        /** Reports sources that failed; their failure yields no items. */
        val logger: Logger = LoggerFactory.getLogger(DefaultCompletionSessionModel::class.java)
    }
}
