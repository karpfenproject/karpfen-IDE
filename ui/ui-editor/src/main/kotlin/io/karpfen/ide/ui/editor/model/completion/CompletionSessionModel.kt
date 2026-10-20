package io.karpfen.ide.ui.editor.model.completion

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.action.EditorActionExecutor
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

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

internal class DefaultCompletionSessionModel(
    private val text: EditableText,
    private val caret: CaretModel,
    private val source: CompletionSource,
    private val executor: EditorActionExecutor,
    private val scope: CoroutineScope,
) : CompletionSessionModel {
    override val state: StateFlow<CompletionUiState?>
        get() = TODO("WP-EDITOR-MODEL: completion state")

    override fun open(trigger: CompletionTrigger): Unit = TODO("WP-EDITOR-MODEL: open completion")

    override fun onTyped(text: String): Unit = TODO("WP-EDITOR-MODEL: auto-trigger and filter")

    override fun moveSelection(delta: Int): Unit = TODO("WP-EDITOR-MODEL: move selection")

    override fun select(index: Int): Unit = TODO("WP-EDITOR-MODEL: select item")

    override fun accept(): Boolean = TODO("WP-EDITOR-MODEL: accept item")

    override fun close(): Unit = TODO("WP-EDITOR-MODEL: close completion")

    override fun handleKey(stroke: KeyStroke): Boolean = TODO("WP-EDITOR-MODEL: completion keys")
}
