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

import io.karpfen.ide.core.text.TextRange
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** The matches of a query in one text. For an invalid regular expression, [error] holds the message and [matches] is empty. */
internal data class MatchOutcome(val matches: List<TextRange>, val error: String?)

/**
 * The search rules of find and replace. The class holds pure functions and no state.
 *
 * Each query becomes one [Pattern]. It searches for the literal text, unless [FindQuery.regex] is set. Then the text is
 * a regular expression in multi-line mode, where `^` and `$` match at line bounds. Matching ignores case with Unicode
 * rules, unless [FindQuery.caseSensitive] is set. With [FindQuery.wholeWord], a match must not have an identifier
 * character right before or after it. Identifier characters are `[A-Za-z0-9_]`, the word rule of the caret model.
 *
 * Matches do not overlap and come in document order. Empty matches are skipped. An invalid regular expression becomes
 * a [MatchOutcome.error] and never an exception.
 */
internal class FindMatcher {

    /**
     * Returns the matches of [query] in [text]. [checkCancelled] is called regularly while matching. The background
     * search passes `ensureActive`, so a newer query or text version stops a long regular expression search.
     */
    fun find(text: String, query: FindQuery, checkCancelled: () -> Unit = {}): MatchOutcome {
        if (query.text.isEmpty()) return MatchOutcome(emptyList(), null)
        val pattern = try {
            compile(query)
        } catch (e: PatternSyntaxException) {
            return MatchOutcome(emptyList(), describe(e))
        }
        val matcher = pattern.matcher(CheckedText(text, checkCancelled))
        val matches = ArrayList<TextRange>()
        while (matcher.find()) {
            if (matcher.end() > matcher.start()) matches += TextRange(matcher.start(), matcher.end())
            if (matches.size % CHECK_INTERVAL == 0) checkCancelled()
        }
        return MatchOutcome(matches, null)
    }

    /**
     * Returns the texts that replace [matches], in order. [matches] are the matches of [query] in [text]. Outside regex
     * mode every text is [replacement] as it is. In regex mode the group references `$n` and `${name}` are expanded,
     * and `\` escapes a character. A replacement that cannot be expanded, such as one with a reference to a missing
     * group or a trailing `\`, is inserted literally everywhere. The expansion makes one pass over [text] and repeats
     * the search. [checkCancelled] is called regularly, as in [find].
     */
    fun replacements(
        text: String,
        query: FindQuery,
        matches: List<TextRange>,
        replacement: String,
        checkCancelled: () -> Unit = {},
    ): List<String> {
        if (!query.regex || matches.isEmpty()) return matches.map { replacement }
        return try {
            expandAll(compile(query).matcher(CheckedText(text, checkCancelled)), matches, replacement)
        } catch (e: PatternSyntaxException) {
            matches.map { replacement }
        } catch (e: IllegalArgumentException) {
            matches.map { replacement }
        } catch (e: IndexOutOfBoundsException) {
            matches.map { replacement }
        }
    }

    /**
     * Expands [replacement] for each of [matches] while [matcher] walks the text once with `appendReplacement`. Matches
     * that the walk does not meet keep the unexpanded [replacement].
     */
    private fun expandAll(matcher: Matcher, matches: List<TextRange>, replacement: String): List<String> {
        val result = ArrayList<String>(matches.size)
        val buffer = StringBuilder()
        var appended = 0
        var index = 0
        while (index < matches.size && matcher.find()) {
            val match = matches[index]
            if (matcher.start() != match.start || matcher.end() != match.end) continue
            val expansionStart = buffer.length + (matcher.start() - appended)
            matcher.appendReplacement(buffer, replacement)
            result += buffer.substring(expansionStart)
            appended = matcher.end()
            index++
        }
        while (result.size < matches.size) result += replacement
        return result
    }

    /**
     * Compiles the pattern of [query]. It is the quoted text or the regular expression. For a whole-word query, it is
     * wrapped in look-arounds that reject identifier characters on either side.
     */
    private fun compile(query: FindQuery): Pattern {
        val body = if (query.regex) query.text else Pattern.quote(query.text)
        val bounded = if (query.wholeWord) "(?<![A-Za-z0-9_])(?:$body)(?![A-Za-z0-9_])" else body
        var flags = if (query.regex) Pattern.MULTILINE else 0
        if (!query.caseSensitive) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return Pattern.compile(bounded, flags)
    }

    /** Returns the message shown for an invalid regular expression. It holds the description and, if known, the index of the error. */
    private fun describe(e: PatternSyntaxException): String =
        if (e.index >= 0) "${e.description} near index ${e.index}" else e.description

    /** Wraps [text] for the regex engine and calls [checkCancelled] every [CHECK_INTERVAL] character reads. */
    private class CheckedText(private val text: String, private val checkCancelled: () -> Unit) : CharSequence {
        private var reads = 0

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            if (++reads % CHECK_INTERVAL == 0) checkCancelled()
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)

        override fun toString(): String = text
    }

    /** How often matching checks for cancellation. */
    private companion object {
        /** The number of character reads, and of matches, between two cancellation checks. */
        const val CHECK_INTERVAL = 4096
    }
}
