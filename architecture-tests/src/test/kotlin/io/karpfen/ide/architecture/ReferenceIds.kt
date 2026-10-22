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
 * of truth". The ids recognized here are those the planning documents of the project use:
 *
 * - a section sign followed by a section number;
 * - the word "decision" followed by a number, in any case and also in the plural;
 * - a work-package id, the letters WP, a hyphen and a name in letters;
 * - the abbreviation ADR followed by a number, with or without a blank, hyphen or underscore in between;
 * - a standalone decision key, the capital letter D followed by a single digit from one to eight;
 * - the words "spec section" or "specification section" followed by a number, in any case.
 *
 * Ordinary prose does not match: a capital D with a digit inside a longer word, such as a hexadecimal color, is no
 * standalone key, and the word "decision" without a number is a plain word.
 */
internal object ReferenceIds {

    /** One pattern per kind of id, in the order of the list in the object documentation. */
    private val patterns: List<Regex> = listOf(
        Regex("""§\s*\d+(?:\.\d+)*"""),
        Regex("""\bdecisions?\s+#?\d+""", RegexOption.IGNORE_CASE),
        Regex("""\bWP-[A-Za-z]+"""),
        Regex("""\bADR[\s_-]*\d+"""),
        Regex("""\bD[1-8]\b"""),
        Regex("""\bspec(?:ification)?\s+section\s+\d+(?:\.\d+)*""", RegexOption.IGNORE_CASE),
    )

    /** The reference ids in [text], ordered by their offset. */
    fun findIn(text: String): List<ReferenceId> =
        patterns
            .flatMap { pattern -> pattern.findAll(text).map { ReferenceId(it.value, it.range.first) } }
            .sortedBy { it.offset }
}
