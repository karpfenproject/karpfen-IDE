package io.karpfen.ide.architecture

/**
 * A reference id found in documentation text.
 *
 * @property text the id as written, for example a section sign with its section number.
 * @property offset the offset of the id in the text that was searched.
 */
internal data class ReferenceId(val text: String, val offset: Int)

/**
 * Recognizes the ids by which documentation might refer to other documents instead of naming what it means.
 *
 * A reader of the code should not have to look up an id to understand a sentence, so documentation names the thing in
 * words, for example "the parse budget of the phase-1 specification" or "the decision that text files are the source
 * of truth". The ids recognized here are those the planning documents and the reviews of the project use:
 *
 * - a section sign followed by a section number;
 * - the word "section" followed by a section number, also after "spec" or "specification", in any case;
 * - the word "decision" followed by a number, in any case and also in the plural;
 * - a work-package id, the letters WP, a hyphen and a name in letters, and the words "work package" followed by a
 *   name in capitals or by a number;
 * - the abbreviation ADR followed by a number, with or without a blank, hyphen or underscore in between;
 * - the words "review round" followed by a number, and the key of a review finding, words joined by hyphens, quoted
 *   or in backticks after the word "review" or after a round, or after the words "review finding" also unquoted;
 * - a short key, the capital letter D, E or P followed by a number, as the planning documents name decisions, findings
 *   and phases, where the text uses it as a reference: after the word decision, finding, phase or key (a list of keys
 *   joined by commas, "and" or "or" included), alone or in a list in parentheses, or followed by a colon.
 *
 * Ordinary prose does not match. A short key standing alone is no reference, because letters with digits are common
 * names: a function key, the points of a curve, a library or a language such as the diagram language D2. A capital D
 * with a digit inside a longer word, such as a hexadecimal color, is no key either, the word "decision" without a
 * number is a plain word, and the kebab-case codes of diagnostics are no finding keys unless a review is named.
 */
internal object ReferenceIds {

    /** A short key: one of the capital letters of the planning documents' keys followed by a number. */
    private const val KEY = """[DEP]\d+"""

    /** The key of a review finding: lowercase words or numbers joined by hyphens. */
    private const val FINDING_KEY = """[a-z0-9]+(?:-[a-z0-9]+)+"""

    /**
     * One pattern per kind of id, in the order of the list in the object documentation. Where two patterns match at
     * the same offset, the earlier one wins, so a pattern that extends another comes first.
     */
    private val patterns: List<String> = listOf(
        """§\s*\d+(?:\.\d+)*""",
        """(?i:\b(?:spec(?:ification)?\s+)?sections?\s+\d+(?:\.\d+)*)""",
        """(?i:\bdecisions?\s+#?\d+)""",
        """\bWP-[A-Za-z]+""",
        """(?i:\bwork[\s-]+packages?)\s+(?:[A-Z]{2,}[A-Z0-9-]*|\d+)\b""",
        """\bADR[\s_-]*\d+""",
        """(?i:\breview\s+round)\s+\d+(?:\s*,\s*["`]$FINDING_KEY["`])?""",
        """(?i:\breview\s+findings?)\s*:?\s*["`]?$FINDING_KEY["`]?""",
        """(?i:\breview)\s*:?\s*["`]$FINDING_KEY["`]""",
        """(?i:\b(?:decisions?|findings?|phases?|keys?))\s+$KEY(?:(?:\s*,\s*|\s+(?:and|or)\s+)$KEY)*\b""",
        """\(\s*$KEY(?:\s*[,;/]\s*$KEY)*\s*\)""",
        """\b$KEY(?=:)""",
    )

    /** All [patterns] as one alternative, so overlapping ids are reported once, as the longest pattern found them. */
    private val anyId = Regex(patterns.joinToString("|") { "(?:$it)" })

    /** The reference ids in [text], ordered by their offset. */
    fun findIn(text: String): List<ReferenceId> = anyId.findAll(text).map { ReferenceId(it.value, it.range.first) }.toList()
}
