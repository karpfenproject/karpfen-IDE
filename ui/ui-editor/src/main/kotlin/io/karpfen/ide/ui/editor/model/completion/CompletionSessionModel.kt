package io.karpfen.ide.ui.editor.model.completion

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.text.Bias
import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextEdit
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

/** Why completion opened (spec §7.1). */
public enum class CompletionTrigger {
    /** Explicit request (Ctrl+Space). */
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
}

/**
 * One editor's completion session (spec §7.1). Compose-free state holder used from the UI thread.
 *
 * - Opens on [open] (explicit) or auto-triggers from [onTyped]: an identifier character typed after ≥ 1 identifier
 *   character, `"` in a completable string position, `$(` or `->`. Opening with no items keeps it closed.
 * - Asks the [CompletionSource] for the snapshot current at the request in a coroutine launched in its scope (the
 *   source switches threads itself; a newer request cancels an older one) and drops results computed for an older
 *   version (re-requesting while still open), so `replaceRange`s always match the text they are applied to. Tests use
 *   a test dispatcher in the scope and a fake source; nothing runs on real threads.
 * - Filters by the text between `replaceRange.start` and the caret, case-insensitively: prefix matches before
 *   camel/word-boundary matches before subsequence matches; stable sort by match quality, then sortKey, then label.
 * - [accept] inserts `insertText` over the replace range as one `EditorAction` run through the factory's
 *   [EditorActionExecutor] (one undo step, caret mementos) and places the caret at `caretOffsetInInsert`.
 * - Follows `CaretModel.state` itself: closes when the primary caret leaves the replace range, when no item matches,
 *   on Escape and on focus loss ([close]).
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
 * Default [CompletionSessionModel], a coordinator: [CompletionTriggers] decides auto-triggers, [CompletionRanking]
 * filters and ranks, `CommandEditAction` carries the accepted edit to the executor; this class owns the request and the
 * open session.
 *
 * A session keeps the source's items with the text version they were computed for and its range: the first item's
 * replace range extended to the request offset. Typing extends the range — its start is anchored with `Bias.LEFT` and
 * its end with `Bias.RIGHT` (`EditableText.mapOffset`, the point mapping of anchors), because text typed at either
 * bound belongs to the word being completed — so the items are re-filtered locally on every caret change without a new
 * request, and [accept] maps the selected item's range the same way into the current text.
 */
internal class DefaultCompletionSessionModel(
    private val text: EditableText,
    private val caret: CaretModel,
    private val source: CompletionSource,
    private val executor: EditorActionExecutor,
    private val scope: CoroutineScope,
) : CompletionSessionModel {

    /** The items of an open session, computed for text [version]; [start]/[end] is the session range in that version. */
    private class Session(val items: List<CompletionItem>, val version: Long, val start: Int, val end: Int)

    private val uiState = MutableStateFlow<CompletionUiState?>(null)
    private var session: Session? = null
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
        val current = uiState.value ?: return false
        val open = session ?: return false
        val item = current.selected
        val snapshot = text.snapshot.value
        val position = caret.primary.position
        val start = anchor(item.replaceRange.start, open.version, Bias.LEFT)
        val end = anchor(item.replaceRange.end, open.version, Bias.RIGHT)
        close()
        if (start == null || end == null) return false
        val range = TextRange(minOf(start, position), maxOf(end, position))
        val caretAfter = range.start + (item.caretOffsetInInsert ?: item.insertText.length)
        val action = CommandEditAction(
            ACCEPT_ID, "Accept Completion", snapshot.version, listOf(TextEdit(range, item.insertText)),
            listOf(CaretMemento.at(caretAfter)),
        )
        return executor.execute(action)
    }

    override fun close() {
        request?.cancel()
        request = null
        dismiss()
    }

    override fun handleKey(stroke: KeyStroke): Boolean {
        val open = uiState.value != null
        if (stroke.modifiers.isNotEmpty()) return false
        when (stroke.key) {
            KeyCode.ESCAPE -> close()
            KeyCode.UP -> if (open) moveSelection(-1)
            KeyCode.DOWN -> if (open) moveSelection(1)
            KeyCode.PAGE_UP -> if (open) moveSelection(-PAGE_SIZE)
            KeyCode.PAGE_DOWN -> if (open) moveSelection(PAGE_SIZE)
            KeyCode.ENTER, KeyCode.TAB -> if (open) accept()
            else -> return false
        }
        return open
    }

    /** Asks the source for the items at the primary caret of the current text; a newer request cancels this one. */
    private fun request(trigger: CompletionTrigger) {
        request?.cancel()
        val snapshot = text.snapshot.value
        val offset = caret.primary.position
        // Started after the assignment, so an eager dispatcher that completes the request at once (and may start a
        // newer one from receive) never leaves a finished job in `request`.
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

    /** Installs [items] computed for [snapshot] at [offset]; results for an older version are dropped and re-requested. */
    private fun receive(trigger: CompletionTrigger, snapshot: TextSnapshot, offset: Int, items: List<CompletionItem>) {
        if (snapshot.version != text.snapshot.value.version) {
            request(trigger)
            return
        }
        if (items.isEmpty()) {
            dismiss()
            return
        }
        val range = items.first().replaceRange
        session = Session(items, snapshot.version, minOf(range.start, offset), maxOf(range.end, offset))
        uiState.value = null
        refresh()
    }

    /** Re-filters the open session for the current text and caret, or closes it (caret outside, nothing matches). */
    private fun refresh() {
        val open = session ?: return
        val snapshot = text.snapshot.value
        val primary = caret.primary
        val start = anchor(open.start, open.version, Bias.LEFT)
        val end = anchor(open.end, open.version, Bias.RIGHT)
        if (start == null || end == null || primary.hasSelection || primary.position !in start..end) {
            dismiss()
            return
        }
        val filter = snapshot.text.substring(start, primary.position)
        val items = CompletionRanking.rank(open.items, filter)
        if (items.isEmpty()) {
            dismiss()
            return
        }
        val previous = uiState.value
        val kept = previous?.takeIf { it.filterText == filter }?.let { items.indexOf(it.selected) } ?: -1
        uiState.value = CompletionUiState(items, if (kept >= 0) kept else 0, TextRange(start, end), filter)
    }

    /** [offset] of [version] in the current text with [bias], or null when the history no longer reaches [version]. */
    private fun anchor(offset: Int, version: Long, bias: Bias): Int? {
        val current = text.snapshot.value.version
        return if (version > current) null else text.mapOffset(offset, version, current, bias)
    }

    /** Closes the visible session; a running request stays (it may open a new session). */
    private fun dismiss() {
        session = null
        uiState.value = null
    }

    private companion object {
        const val ACCEPT_ID = "editor.completion.accept"

        /** Rows moved by Page Up/Down: the popup shows at most 12 rows (spec §7.2). */
        const val PAGE_SIZE = 12

        val logger: Logger = LoggerFactory.getLogger(DefaultCompletionSessionModel::class.java)
    }
}
