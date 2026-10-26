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

/** How well a label matches the typed filter text; lower is better. */
internal enum class MatchQuality {
    /** The label starts with the filter (case-insensitive). */
    PREFIX,

    /** The filter's characters start words of the label (camel humps, `_`, `-`, `>`, `.`, spaces, digits). */
    WORD_BOUNDARY,

    /** The filter's characters occur in the label in order. */
    SUBSEQUENCE,
}

/**
 * Filtering and ranking of completion items, pure and stateless: case-insensitive prefix matches before
 * camel/word-boundary matches before subsequence matches; items that match none are dropped. The sort is stable — by
 * match quality, then `sortKey`, then label, then the source's order — so equally good items never jump around while
 * the user types.
 */
internal object CompletionRanking {

    /** The items matching [filter], best first. */
    fun rank(items: List<CompletionItem>, filter: String): List<CompletionItem> =
        items.mapNotNull { item -> qualityOf(item.label, filter)?.let { item to it } }
            .sortedWith(compareBy<Pair<CompletionItem, MatchQuality>> { it.second }.thenBy { it.first.sortKey }.thenBy { it.first.label })
            .map { it.first }

    /** How [label] matches [filter], or null if it does not. */
    fun qualityOf(label: String, filter: String): MatchQuality? = when {
        label.startsWith(filter, ignoreCase = true) -> MatchQuality.PREFIX
        matchesWordStarts(label, filter) -> MatchQuality.WORD_BOUNDARY
        isSubsequence(label, filter) -> MatchQuality.SUBSEQUENCE
        else -> null
    }

    /**
     * True if [filter] can be split into pieces that each match (case-insensitively) a run of [label] starting at a
     * word start, in order: every filter character either continues the run of the previous one or starts a new run at
     * a later word start. `sP` matches `simpleProperty`, `pos` matches `robot->position`.
     */
    private fun matchesWordStarts(label: String, filter: String): Boolean {
        if (filter.isEmpty()) return true
        val failed = HashSet<Long>()

        fun match(filterIndex: Int, labelIndex: Int, continuing: Boolean): Boolean {
            if (filterIndex == filter.length) return true
            if (labelIndex >= label.length) return false
            val key = (filterIndex.toLong() shl 32) or (labelIndex.toLong() shl 1) or (if (continuing) 1L else 0L)
            if (key in failed) return false
            val equal = label[labelIndex].equals(filter[filterIndex], ignoreCase = true)
            val matched = (continuing && equal && match(filterIndex + 1, labelIndex + 1, true)) ||
                (wordStartsAt(label, labelIndex) && equal && match(filterIndex + 1, labelIndex + 1, true)) ||
                match(filterIndex, labelIndex + 1, false)
            if (!matched) failed += key
            return matched
        }
        return match(0, 0, false)
    }

    /**
     * Whether a word of [label] starts at [index]: at the label start, at a letter or digit after other characters, at
     * an upper-case letter after a lower-case one (camel hump), or at a digit after a non-digit.
     */
    private fun wordStartsAt(label: String, index: Int): Boolean {
        if (index == 0) return true
        val previous = label[index - 1]
        val current = label[index]
        return !previous.isLetterOrDigit() && current.isLetterOrDigit() ||
            previous.isLowerCase() && current.isUpperCase() ||
            !previous.isDigit() && current.isDigit()
    }

    /** Whether the characters of [filter] occur in [label] in order (case-insensitively). */
    private fun isSubsequence(label: String, filter: String): Boolean {
        var index = 0
        for (char in label) {
            if (index < filter.length && char.equals(filter[index], ignoreCase = true)) index++
        }
        return index == filter.length
    }
}
