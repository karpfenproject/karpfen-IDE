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
 * of truth". The ids recognized here are those the planning documents, the reviews and the earlier comments of the
 * project used:
 *
 * - a section sign followed by a section number;
 * - the word "section" followed by a section number, also after "spec" or "specification", in any case, and the
 *   abbreviations "sec." and "sect." followed by a number;
 * - the word "decision" followed by a number, in any case and also in the plural, and the word "record", also after
 *   "decision", followed by a number;
 * - a numbered rule, step or item of a document: the word and its number followed by "of the" and the name of a plan,
 *   proposal, specification or record, with up to three words in between, or after the possessive of such a document;
 * - a work-package id: the letters WP followed by a hyphen and a name in letters, or by a number with or without a
 *   hyphen; the words "work package" followed by a name in capitals or by a number; and the name of a work package of
 *   the phase-1 plan in capitals with a possessive, as the comments of phase 1 wrote it. These names are listed
 *   ([WORK_PACKAGES]), because acronyms such as ANTLR and the keywords of the modeling languages have the same shape;
 * - the abbreviation ADR followed by a number, with or without a blank, hyphen or underscore in between;
 * - the words "review round" followed by a number, and the key of a review finding, words joined by hyphens, quoted
 *   or in backticks after the word "review" or after a round, or after the words "review finding" also unquoted;
 * - a short key, the capital letter D, E or P followed by a number, as the planning documents name decisions, findings
 *   and phases, where the text uses it as a reference: after or before the word decision, finding or phase, after the
 *   word key (a list of keys joined by commas, "and" or "or" included), alone or in a list in parentheses, or followed
 *   by a colon.
 *
 * Ordinary prose does not match. A short key standing alone is no reference, because letters with digits are common
 * names: a function key, the points of a curve, a library or a language such as the diagram language D2. A capital D
 * with a digit inside a longer word, such as a hexadecimal color, is no key either, the word "decision" without a
 * number is a plain word, a numbered step of an algorithm is no reference unless a document is named, and the
 * kebab-case codes of diagnostics are no finding keys unless a review is named.
 */
internal object ReferenceIds {

    /** A short key: one of the capital letters of the planning documents' keys followed by a number. */
    private const val KEY = """[DEP]\d+"""

    /** The key of a review finding: lowercase words or numbers joined by hyphens. */
    private const val FINDING_KEY = """[a-z0-9]+(?:-[a-z0-9]+)+"""

    /** The names of the work packages of the phase-1 plan, as its headings write them. */
    private val WORK_PACKAGES = listOf(
        "TEXT", "FOUNDATION", "TOKENS", "PARSE", "EDITOR-MODEL", "WORKSPACE", "LANG", "EDITOR-VIEW", "EDITOR-POPUPS",
        "WORKBENCH", "APP", "INTEGRATION",
    )

    /** The documents whose numbered rules, steps and items are referred to: plans, proposals, specifications, records. */
    private const val DOCUMENT = """(?:plan|proposal|spec(?:ification)?|record)s?"""

    /**
     * One pattern per kind of id, in the order of the list in the object documentation. Where two patterns match at
     * the same offset, the earlier one wins, so a pattern that extends another comes first.
     */
    private val patterns: List<String> = listOf(
        """§\s*\d+(?:\.\d+)*""",
        """(?i:\b(?:spec(?:ification)?\s+)?sections?\s+\d+(?:\.\d+)*)""",
        """(?i:\bsect?\.\s*\d+(?:\.\d+)*)""",
        """(?i:\bdecisions?\s+#?\d+)""",
        """(?i:\b(?:decision\s+records?|record)\s+#?\d+)""",
        """(?i:\b(?:rules?|steps?|items?)\s+\d+(?:\s*(?:[-–,]|and|or)\s*\d+)*\s+of\s+the\s+(?:[\w'-]+\s+){0,3}?$DOCUMENT\b)""",
        """(?i:\b$DOCUMENT['’]s\s+(?:rules?|steps?|items?)\s+\d+)""",
        """\bWP(?:-[A-Za-z]+|-?\d+)""",
        """(?i:\bwork[\s-]+packages?)\s+(?:[A-Z]{2,}[A-Z0-9-]*|\d+)\b""",
        """(?<![\w-])(?:${WORK_PACKAGES.joinToString("|")})['’]s\b""",
        """\bADR[\s_-]*\d+""",
        """(?i:\breview\s+round)\s+\d+(?:\s*,\s*["`]$FINDING_KEY["`])?""",
        """(?i:\breview\s+findings?)\s*:?\s*["`]?$FINDING_KEY["`]?""",
        """(?i:\breview)\s*:?\s*["`]$FINDING_KEY["`]""",
        """(?i:\b(?:decisions?|findings?|phases?|keys?))\s+$KEY(?:(?:\s*,\s*|\s+(?:and|or)\s+)$KEY)*\b""",
        """\b$KEY(?:(?:\s*,\s*|\s+(?:and|or)\s+)$KEY)*\s+(?i:decisions?|findings?|phases?)\b""",
        """\(\s*$KEY(?:\s*[,;/]\s*$KEY)*\s*\)""",
        """\b$KEY(?=:)""",
    )

    /** All [patterns] as one alternative, so overlapping ids are reported once, as the longest pattern found them. */
    private val anyId = Regex(patterns.joinToString("|") { "(?:$it)" })

    /** The reference ids in [text], ordered by their offset. */
    fun findIn(text: String): List<ReferenceId> = anyId.findAll(text).map { ReferenceId(it.value, it.range.first) }.toList()
}
