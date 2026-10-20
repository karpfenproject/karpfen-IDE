package io.karpfen.ide.ui.editor.model.find

import io.karpfen.ide.core.text.TextRange
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** The matches of a query in one text, or the [error] of an invalid regular expression (then [matches] is empty). */
internal data class MatchOutcome(val matches: List<TextRange>, val error: String?)

/**
 * The search rules of find/replace (spec §7.1), pure and stateless: one [Pattern] per query — the literal text unless
 * [FindQuery.regex] (then multi-line mode, `^`/`$` at line bounds), case-insensitive (Unicode) unless
 * [FindQuery.caseSensitive], and with [FindQuery.wholeWord] bounded by non-identifier characters (`[A-Za-z0-9_]`, the
 * word rule of the caret model). Matches are non-overlapping, in document order; empty matches are skipped. An invalid
 * regular expression is an [MatchOutcome.error], never an exception.
 */
internal class FindMatcher {

    /**
     * The matches of [query] in [text]. [checkCancelled] is called regularly while matching (the background search
     * passes `ensureActive`, so a newer query or version stops a long regular-expression search).
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
     * The texts replacing [matches] (the matches of [query] in [text], in order): [replacement] as is, or in regex mode
     * with `$n`/`${name}` group references expanded (`\` escapes). A replacement that cannot be expanded (a reference
     * to a missing group, a trailing `\`) is inserted literally everywhere. One pass over [text].
     */
    fun replacements(text: String, query: FindQuery, matches: List<TextRange>, replacement: String): List<String> {
        if (!query.regex || matches.isEmpty()) return matches.map { replacement }
        return try {
            expandAll(compile(query).matcher(text), matches, replacement)
        } catch (e: PatternSyntaxException) {
            matches.map { replacement }
        } catch (e: IllegalArgumentException) {
            matches.map { replacement }
        } catch (e: IndexOutOfBoundsException) {
            matches.map { replacement }
        }
    }

    /** Expands [replacement] for each of [matches] while [matcher] walks the text once (`appendReplacement`). */
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

    private fun compile(query: FindQuery): Pattern {
        val body = if (query.regex) query.text else Pattern.quote(query.text)
        val bounded = if (query.wholeWord) "(?<![A-Za-z0-9_])(?:$body)(?![A-Za-z0-9_])" else body
        var flags = if (query.regex) Pattern.MULTILINE else 0
        if (!query.caseSensitive) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return Pattern.compile(bounded, flags)
    }

    private fun describe(e: PatternSyntaxException): String =
        if (e.index >= 0) "${e.description} near index ${e.index}" else e.description

    /** [text] for the regex engine, calling [checkCancelled] every [CHECK_INTERVAL] reads. */
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

    private companion object {
        const val CHECK_INTERVAL = 4096
    }
}
