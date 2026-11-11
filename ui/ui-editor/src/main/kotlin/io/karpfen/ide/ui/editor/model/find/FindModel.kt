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
package io.karpfen.ide.ui.editor.model.find

import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorActionExecutor
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * What to search for. An empty [text] matches nothing.
 *
 * @property text the searched text, or the regular expression when [regex] is set.
 * @property caseSensitive whether letter case must match. If false, matching ignores case with Unicode rules.
 * @property wholeWord whether a match must not be preceded or followed by an identifier character.
 * @property regex whether [text] is a regular expression. It runs in multi-line mode, so `^` and `$` match at line
 *   bounds.
 */
public data class FindQuery(
    public val text: String = "",
    public val caseSensitive: Boolean = false,
    public val wholeWord: Boolean = false,
    public val regex: Boolean = false,
)

/**
 * The find state for one text version.
 *
 * @property query the query these matches belong to. It lags behind [FindModel.query] while the matches are computed.
 * @property version the text version the matches were computed for.
 * @property matches the matches in document order. They do not overlap, and empty regex matches are skipped.
 * @property currentIndex the index of the current match in [matches], or null if there is none.
 * @property error the message of an invalid regular expression or of a search that took too long, or null. When it is
 *   set, [matches] is empty.
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

    /** The one-based number of the current match for the "n/m" counter, or 0 if there is none. */
    public val currentNumber: Int get() = currentIndex?.plus(1) ?: 0

    /** The number of matches. */
    public val total: Int get() = matches.size
}

/**
 * Find and replace in one editor. It is a state holder without Compose, used from the UI thread.
 *
 * [query] is the query the model searches for. [setQuery] sets it synchronously. The matches follow the text and the
 * query. They are computed for every new version and query on the background dispatcher passed to the factory. The
 * latest computation wins, it can be cancelled, and it has a time budget. A regular expression that takes too long ends
 * in an error state. The results are published as [state] in the model's scope. Invalid regular expressions also put
 * the model into an error state. Nothing throws.
 *
 * No matching happens on the UI thread. [next], [previous] and the replace functions act on the matches of the current
 * text and query. When the published matches lag behind, for example right after an edit or a new query, the operation
 * waits. Waiting operations run in call order once the matches are published. A new query drops the operations that
 * still wait for the old one. Regex replacements are expanded on the background dispatcher too.
 *
 * Every selection and every edit goes through the [EditorActionExecutor] passed to the factory, following the Command
 * pattern. Selecting a match runs an internal select-match `EditorAction`, which changes only the caret and makes no
 * undo step. Replacing runs a replace action with origin `Command`, caret mementos and one undo step. The editor state
 * reveals the caret after every executed action. So a selected match is always scrolled into view, no matter whether
 * the find bar, the find next key or the menu asked for it.
 */
public interface FindModel {

    /**
     * The query the model searches for. [setQuery] sets it synchronously. The find bar renders it, and find next and
     * the seeding of the query from the selection read it. [FindResult.query] of [state] follows it once its matches
     * are computed.
     */
    public val query: StateFlow<FindQuery>

    /** The matches of the current text for [FindResult.query], which is the latest computed query, and the current match. */
    public val state: StateFlow<FindResult>

    /**
     * The replacement text of the find and replace bar, set by [setReplacement]. Like [query], it lives as long as the
     * model, which lives as long as the editor. So it survives closing the bar and a new composition of the editor, for
     * example after switching tabs. The replace functions take their replacement as an argument, and the bar passes
     * this one.
     */
    public val replacement: StateFlow<String>

    /**
     * Replaces the query for incremental search. Typing in the find field calls this for every change. Once the matches
     * of the new query are known, the current match is the first match at or after the start of the primary selection.
     * So a match that is already selected stays current. The current match is then selected through the executor.
     * Without matches, the selection stays as it is. Operations that still wait for the matches of the previous query
     * are dropped.
     */
    public fun setQuery(query: FindQuery)

    /** Replaces [replacement]. Typing in the replacement field calls this for every change. */
    public fun setReplacement(replacement: String)

    /**
     * Find next. If the primary selection is not exactly the current match yet, it selects the current match.
     * Otherwise it moves to the next match, wrapping around at the end, and selects it. Without matches it does
     * nothing. If the matches of the current text and query are not published yet, it waits for them.
     */
    public fun next()

    /** Find previous. It works like [next], but moves to the previous match and wraps around at the start. */
    public fun previous()

    /**
     * Replaces the current match with [replacement] and moves to the next match. In regex mode the replacement may hold
     * group references such as `$1`. It waits like [next]. A regex replacement is expanded on the background
     * dispatcher before the edit.
     */
    public fun replaceCurrent(replacement: String)

    /** Replaces every match as one undo step. It waits and expands like [replaceCurrent]. */
    public fun replaceAll(replacement: String)
}

/**
 * Creates a [FindModel] over [text] that selects matches through [caret] and replaces through [executor]. The state is
 * published in [scope], which is the UI scope of the editor. Matches are computed on [backgroundDispatcher]. The editor
 * state passes its own background dispatcher, and tests pass a test dispatcher.
 */
public fun FindModel(
    text: EditableText,
    caret: CaretModel,
    executor: EditorActionExecutor,
    scope: CoroutineScope,
    backgroundDispatcher: CoroutineDispatcher,
): FindModel = DefaultFindModel(text, caret, executor, scope, backgroundDispatcher)
