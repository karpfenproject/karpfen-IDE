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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/** An operation of [FindModel] that acts on the matches of the current text and query (Command). */
internal sealed interface FindOperation {
    /** [FindModel.next]. */
    data object Next : FindOperation

    /** [FindModel.previous]. */
    data object Previous : FindOperation

    /** [FindModel.replaceCurrent] ([all] false) or [FindModel.replaceAll]. */
    data class Replace(val replacement: String, val all: Boolean) : FindOperation
}

/**
 * Default [FindModel], a coordinator: [FindSearch] computes matches and expands regex replacements off the UI thread,
 * [SelectMatchAction] and `CommandEditAction` carry every selection and edit to the executor; this class owns the
 * query, the published [FindResult] and the operations waiting for it.
 *
 * Pipeline: the newest text snapshot and query are matched by [search] (latest wins: a running search stops when a
 * newer one starts) and published in [scope] while they are still current. Operations ([FindOperation]) run at once
 * while the published result is current ([isFresh]) and otherwise wait in [pending] until the next publish; a regex
 * replacement suspends the queue while it is expanded in the background ([expanding]) and runs again for the current
 * text if the text changed meanwhile. The current match is always the first match at or after the start of the primary
 * selection (wrapping to the first match), so it follows the caret ([CaretModel.state] is observed) and stays on a
 * match the model selected. A pending selection ([setQuery], replace current) is made by the publish of the matches it
 * waits for, before the waiting operations run.
 *
 * @param searchBudget how long one search or expansion may take (see [FindSearch]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultFindModel(
    private val text: EditableText,
    private val caret: CaretModel,
    private val executor: EditorActionExecutor,
    private val scope: CoroutineScope,
    backgroundDispatcher: CoroutineDispatcher,
    searchBudget: Duration = FindSearch.DEFAULT_BUDGET,
) : FindModel {

    private val search = FindSearch(backgroundDispatcher, searchBudget)
    private val currentQuery = MutableStateFlow(FindQuery())
    private val currentReplacement = MutableStateFlow("")
    private val result = MutableStateFlow(FindResult(FindQuery(), text.snapshot.value.version, emptyList(), null, null))
    private val pending = ArrayDeque<FindOperation>()

    /** The background expansion of a regex replacement; operations wait for it. */
    private var expanding: Job? = null

    /** True from [setQuery] (or a replace current) until the current match was selected (or found to be absent). */
    private var selectPending = false

    init {
        scope.launch {
            combine(text.snapshot, currentQuery) { snapshot, query -> snapshot to query }
                .mapLatest { (snapshot, query) -> Triple(snapshot, query, search.matches(snapshot.text, query)) }
                .collect { (snapshot, query, outcome) -> publish(snapshot, query, outcome) }
        }
        scope.launch { caret.state.collect { followCaret() } }
    }

    override val query: StateFlow<FindQuery> = currentQuery.asStateFlow()

    override val state: StateFlow<FindResult> = result.asStateFlow()

    override val replacement: StateFlow<String> = currentReplacement.asStateFlow()

    override fun setQuery(query: FindQuery) {
        pending.clear()
        expanding?.cancel()
        expanding = null
        currentQuery.value = query
        selectCurrentMatch()
    }

    override fun setReplacement(replacement: String) {
        currentReplacement.value = replacement
    }

    override fun next() = submit(FindOperation.Next)

    override fun previous() = submit(FindOperation.Previous)

    override fun replaceCurrent(replacement: String) = submit(FindOperation.Replace(replacement, all = false))

    override fun replaceAll(replacement: String) = submit(FindOperation.Replace(replacement, all = true))

    /** Queues [operation] behind the waiting ones and runs what can run now. */
    private fun submit(operation: FindOperation) {
        pending.addLast(operation)
        runPending()
    }

    /**
     * Runs the waiting operations in order while the published matches belong to the current text and query; stops
     * while they lag (the next publish continues) and while a replacement is expanded (its end continues).
     */
    private fun runPending() {
        while (expanding == null && isFresh()) {
            val operation = pending.removeFirstOrNull() ?: return
            followCaret()
            when (operation) {
                FindOperation.Next -> step(forward = true)
                FindOperation.Previous -> step(forward = false)
                is FindOperation.Replace -> replace(operation)
            }
        }
    }

    /** [next] or [previous]: select the current match if it is not selected yet, else the following (preceding) one. */
    private fun step(forward: Boolean) {
        val current = result.value
        val index = current.currentIndex ?: return
        val target = when {
            caret.primary.selection != current.matches[index] -> index
            forward -> (index + 1) % current.total
            else -> (index - 1 + current.total) % current.total
        }
        select(target)
    }

    /**
     * Replaces the current match or all matches: at once for a literal replacement, else after a background expansion.
     * The expansion job starts only after it was assigned to [expanding], so a dispatcher that runs it at once never
     * leaves a finished job in [expanding].
     */
    private fun replace(operation: FindOperation.Replace) {
        val current = result.value
        val targets = if (operation.all) current.matches else listOfNotNull(current.current)
        if (targets.isEmpty()) return
        val snapshot = text.snapshot.value
        val literal = search.literalReplacements(current.query, targets, operation.replacement)
        if (literal != null) {
            apply(operation, snapshot, targets, literal)
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val texts = search.replacements(snapshot.text, current.query, targets, operation.replacement)
            expanding = null
            when {
                texts == null -> logger.warn("replacing gave up: expanding {} replacements took too long", targets.size)
                text.snapshot.value.version != snapshot.version -> pending.addFirst(operation)
                else -> apply(operation, snapshot, targets, texts)
            }
            runPending()
        }
        expanding = job
        job.start()
    }

    /** Applies the replacement [texts] of [targets] in [snapshot] as one replace action; replace current moves on. */
    private fun apply(operation: FindOperation.Replace, snapshot: TextSnapshot, targets: List<TextRange>, texts: List<String>) {
        val edits = targets.zip(texts) { match, newText -> TextEdit(match, newText) }
        val action = if (operation.all) {
            CommandEditAction(REPLACE_ALL_ID, "Replace All", snapshot.version, edits, caret.current.carets.map(EditShift(edits)::caret))
        } else {
            val edit = edits.single()
            CommandEditAction(REPLACE_ID, "Replace", snapshot.version, edits, listOf(CaretMemento.at(edit.range.start + edit.newText.length)))
        }
        if (executor.execute(action) && !operation.all) selectCurrentMatch()
    }

    /** Selects the current match now if the matches are current, else once they are published. */
    private fun selectCurrentMatch() {
        selectPending = true
        if (isFresh()) {
            followCaret()
            selectPendingMatch()
        }
    }

    /** Selects match [index] of the published result through the executor (revealed by the editor). */
    private fun select(index: Int) {
        val current = result.value
        result.value = current.copy(currentIndex = index)
        executor.execute(SelectMatchAction(current.matches[index]))
    }

    /** Whether the published result belongs to the current text and the current query. */
    private fun isFresh(): Boolean = result.value.version == text.snapshot.value.version && result.value.query == currentQuery.value

    /** Publishes a background result unless the text or the query moved on, or it is published already. */
    private fun publish(snapshot: TextSnapshot, query: FindQuery, outcome: MatchOutcome) {
        if (snapshot.version != text.snapshot.value.version || query != currentQuery.value || isFresh()) return
        result.value = FindResult(query, snapshot.version, outcome.matches, currentIndexIn(outcome.matches), outcome.error)
        selectPendingMatch()
        runPending()
    }

    /** Makes a pending selection of the current match, if any; without a current match nothing is selected. */
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

    /**
     * The index of the first of [matches] at or after the start of the primary selection, wrapping to the first match;
     * a binary search, since the matches are in document order.
     */
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

    /** The ids of the replace edits and the logger. */
    private companion object {
        /** The id of the internal action that replaces the current match (not registered, not key-bound). */
        const val REPLACE_ID = "editor.find.replace"

        /** The id of the internal action that replaces every match as one undo step. */
        const val REPLACE_ALL_ID = "editor.find.replaceAll"

        /** Reports replacements that gave up because their expansion exceeded the search budget. */
        val logger: Logger = LoggerFactory.getLogger(DefaultFindModel::class.java)
    }
}
