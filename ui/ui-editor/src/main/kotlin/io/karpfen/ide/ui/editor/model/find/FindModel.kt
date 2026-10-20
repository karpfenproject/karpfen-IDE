package io.karpfen.ide.ui.editor.model.find

import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.action.CommandEditAction
import io.karpfen.ide.ui.editor.model.action.EditShift
import io.karpfen.ide.ui.editor.model.action.EditorActionExecutor
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What to search for (spec §7.1). An empty [text] matches nothing. */
public data class FindQuery(
    public val text: String = "",
    public val caseSensitive: Boolean = false,
    public val wholeWord: Boolean = false,
    public val regex: Boolean = false,
)

/**
 * The find state for one text version.
 *
 * @property matches non-overlapping matches in document order (empty matches of a regex are skipped).
 * @property currentIndex index of the current match in [matches], or null if there is none.
 * @property error message of an invalid regular expression (then [matches] is empty), else null.
 */
public data class FindResult(
    public val query: FindQuery,
    public val version: Long,
    public val matches: List<TextRange>,
    public val currentIndex: Int?,
    public val error: String?,
) {
    init {
        require(currentIndex == null || currentIndex in matches.indices) { "currentIndex $currentIndex out of range" }
    }

    /** The current match, or null. */
    public val current: TextRange? get() = currentIndex?.let { matches[it] }

    /** One-based number of the current match for the "n/m" counter (0 if none). */
    public val currentNumber: Int get() = currentIndex?.plus(1) ?: 0

    /** Number of matches. */
    public val total: Int get() = matches.size
}

/**
 * Find and replace in one editor (spec §7.1). Compose-free state holder; matches follow the text: recomputed for every
 * new version and query on the factory's background dispatcher (latest wins) and published in its scope. Invalid
 * regular expressions put the model into an error state; nothing throws. [next], [previous] and the replace functions
 * act on the matches of the current text: when the published state lags behind (right after an edit or a new query),
 * they compute the matches of the current snapshot synchronously first.
 *
 * Every selection and edit goes through the factory's [EditorActionExecutor] (Command pattern, spec §1a): selecting a
 * match runs an internal select-match `EditorAction` (caret only, no undo step), replacing runs a replace action
 * (origin `Command`, caret mementos, one undo step). Because the editor state reveals the caret after every executed
 * action, a selected match is always scrolled into view, whoever asked for it (find bar, FIND_NEXT key, menu).
 */
public interface FindModel {

    /** The current query, matches and selection of the current match. */
    public val state: StateFlow<FindResult>

    /**
     * Replaces the query (incremental search): once the matches of the new query are known, the current match is the
     * first match at or after the start of the primary selection (so a match that is already selected stays current)
     * and it is selected through the executor; without matches the selection stays. Typing in the find field calls
     * this for every change.
     */
    public fun setQuery(query: FindQuery)

    /**
     * Find next: if the primary selection is not exactly the current match yet, selects the current match; otherwise
     * moves to the next match (wrapping) and selects it. No-op without matches.
     */
    public fun next()

    /** Find previous: like [next], moving to the previous match (wrapping). */
    public fun previous()

    /** Replaces the current match by [replacement] (with `$1` group references in regex mode) and moves to the next. */
    public fun replaceCurrent(replacement: String)

    /** Replaces every match as one undo step; returns the number of replacements. */
    public fun replaceAll(replacement: String): Int
}

/**
 * A [FindModel] over [text] that selects matches through [caret] and replaces through [executor]; the state is published
 * in [scope] (the editor's UI scope), matches are computed on [backgroundDispatcher] (the editor state passes its own;
 * tests a test dispatcher).
 */
public fun FindModel(
    text: EditableText,
    caret: CaretModel,
    executor: EditorActionExecutor,
    scope: CoroutineScope,
    backgroundDispatcher: CoroutineDispatcher,
): FindModel = DefaultFindModel(text, caret, executor, scope, backgroundDispatcher)

/**
 * Default [FindModel], a coordinator: [FindMatcher] computes matches and replacement texts, [SelectMatchAction] and
 * `CommandEditAction` carry every selection and edit to the executor; this class owns the query and the published
 * [FindResult].
 *
 * Pipeline: the newest text snapshot and query are matched on [backgroundDispatcher] (latest wins, a running regular
 * expression search stops when a newer one starts) and published in [scope]; a result is only published while it is
 * still current. When [next], [previous] or a replace function find the published state behind the text or the query,
 * they compute it synchronously first. The current match is always the first match at or after the start of the
 * primary selection (wrapping to the first match), so it follows the caret ([CaretModel.state] is observed) and stays
 * on a match the model selected. A pending selection of [setQuery] is made by whichever computation publishes the new
 * query's matches first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultFindModel(
    private val text: EditableText,
    private val caret: CaretModel,
    private val executor: EditorActionExecutor,
    private val scope: CoroutineScope,
    private val backgroundDispatcher: CoroutineDispatcher,
) : FindModel {

    private val matcher = FindMatcher()
    private val query = MutableStateFlow(FindQuery())
    private val result = MutableStateFlow(FindResult(FindQuery(), text.snapshot.value.version, emptyList(), null, null))

    /** True from [setQuery] until the new query's current match was selected (or found to have no matches). */
    private var selectPending = false

    init {
        scope.launch {
            combine(text.snapshot, query) { snapshot, query -> snapshot to query }
                .mapLatest { (snapshot, query) ->
                    val outcome = withContext(backgroundDispatcher) { matcher.find(snapshot.text, query) { ensureActive() } }
                    Triple(snapshot, query, outcome)
                }
                .collect { (snapshot, query, outcome) -> publish(snapshot, query, outcome) }
        }
        scope.launch { caret.state.collect { followCaret() } }
    }

    override val state: StateFlow<FindResult> = result.asStateFlow()

    override fun setQuery(query: FindQuery) {
        selectPending = true
        this.query.value = query
        if (isFresh()) selectPendingMatch()
    }

    override fun next() = step(forward = true)

    override fun previous() = step(forward = false)

    override fun replaceCurrent(replacement: String) {
        ensureFresh()
        val current = result.value
        val match = current.current ?: return
        val snapshot = text.snapshot.value
        val newText = matcher.replacements(snapshot.text, current.query, listOf(match), replacement).single()
        val action = CommandEditAction(
            REPLACE_ID, "Replace", snapshot.version, listOf(TextEdit(match, newText)),
            listOf(CaretMemento.at(match.start + newText.length)),
        )
        if (!executor.execute(action)) return
        ensureFresh()
        result.value.currentIndex?.let(::select)
    }

    override fun replaceAll(replacement: String): Int {
        ensureFresh()
        val current = result.value
        if (current.matches.isEmpty()) return 0
        val snapshot = text.snapshot.value
        val texts = matcher.replacements(snapshot.text, current.query, current.matches, replacement)
        val edits = current.matches.zip(texts) { match, newText -> TextEdit(match, newText) }
        val shift = EditShift(edits)
        val action = CommandEditAction(REPLACE_ALL_ID, "Replace All", snapshot.version, edits, caret.current.carets.map(shift::caret))
        return if (executor.execute(action)) edits.size else 0
    }

    /** [next] or [previous]: select the current match if it is not selected yet, else the following (preceding) one. */
    private fun step(forward: Boolean) {
        ensureFresh()
        val current = result.value
        val index = current.currentIndex ?: return
        val target = when {
            caret.primary.selection != current.matches[index] -> index
            forward -> (index + 1) % current.total
            else -> (index - 1 + current.total) % current.total
        }
        select(target)
    }

    /** Selects match [index] of the published result through the executor (revealed by the editor). */
    private fun select(index: Int) {
        val current = result.value
        result.value = current.copy(currentIndex = index)
        executor.execute(SelectMatchAction(current.matches[index]))
    }

    private fun isFresh(): Boolean = result.value.version == text.snapshot.value.version && result.value.query == query.value

    /** Computes the matches of the current text and query synchronously if the published ones lag behind. */
    private fun ensureFresh() {
        if (isFresh()) {
            followCaret()
            return
        }
        val snapshot = text.snapshot.value
        install(snapshot.version, query.value, matcher.find(snapshot.text, query.value))
    }

    /** Publishes a background result unless the text or the query moved on, or it was computed synchronously already. */
    private fun publish(snapshot: TextSnapshot, query: FindQuery, outcome: MatchOutcome) {
        if (snapshot.version != text.snapshot.value.version || query != this.query.value || isFresh()) return
        install(snapshot.version, query, outcome)
    }

    private fun install(version: Long, query: FindQuery, outcome: MatchOutcome) {
        result.value = FindResult(query, version, outcome.matches, currentIndexIn(outcome.matches), outcome.error)
        selectPendingMatch()
    }

    private fun selectPendingMatch() {
        if (!selectPending) return
        selectPending = false
        result.value.currentIndex?.let(::select)
    }

    /** Keeps the current match on the first match at or after the primary selection's start. */
    private fun followCaret() {
        val current = result.value
        if (current.version != text.snapshot.value.version) return
        val index = currentIndexIn(current.matches)
        if (index != current.currentIndex) result.value = current.copy(currentIndex = index)
    }

    private fun currentIndexIn(matches: List<TextRange>): Int? {
        if (matches.isEmpty()) return null
        val start = caret.primary.selection.start
        var low = 0
        var high = matches.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (matches[middle].start < start) low = middle + 1 else high = middle
        }
        return if (low < matches.size) low else 0
    }

    private companion object {
        const val REPLACE_ID = "editor.find.replace"
        const val REPLACE_ALL_ID = "editor.find.replaceAll"
    }
}
