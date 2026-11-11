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
 * A reader of the code should not have to look up an id to understand a sentence. So documentation names the thing in
 * words, for example "the decision that text files are the source of truth". The ids recognized here are those that
 * planning documents, reviews and earlier comments of the project used:
 *
 * - A section sign followed by a section number.
 * - The word "section" followed by a section number, also after "spec" or "specification", in any case. The
 *   abbreviations "sec." and "sect." followed by a number count too.
 * - The word "decision" followed by a number, in any case and also in the plural. The word "record" followed by a
 *   number counts too, also after "decision".
 * - A numbered rule, step or item of a document. This is the word and its number followed by "of the" and the name
 *   of a plan, proposal, specification or record, with up to three words in between. It is also the word and its
 *   number after the possessive of such a document.
 * - A work-package id. This is the letters WP followed by a hyphen and a name in letters, or by a number with or
 *   without a hyphen. It is also the words "work package" followed by a name in capitals or by a number. And it is
 *   the name of a work package in capitals with a possessive, as earlier comments wrote it. These names are listed
 *   ([WORK_PACKAGES]), because acronyms such as ANTLR and the keywords of the modeling languages have the same shape.
 * - The abbreviation ADR followed by a number, with or without a blank, hyphen or underscore in between.
 * - The words "review round" followed by a number, and the key of a review finding. A finding key is made of words
 *   joined by hyphens. It counts when it is quoted or in backticks after the word "review" or after a round, and also
 *   unquoted after the words "review finding".
 * - A short key, the capital letter D, E or P followed by a number, where the text uses it as a reference. Planning
 *   documents name decisions, findings and phases this way. The key counts after or before the word decision, finding
 *   or phase, and after the word key, also as a list of keys joined by commas, "and" or "or". It also counts alone or
 *   in a list in parentheses, and when a colon follows it.
 * - A numbered phase, increment, milestone, iteration or sprint. This is one of these words, also in the plural,
 *   followed by a blank or a hyphen and a number or a number word from zero to ten, in any case. Documentation says
 *   what a piece of code does, not in which stage of a plan it was written.
 *
 * Ordinary prose does not match. A short key standing alone is no reference, because letters with digits are common
 * names. Examples are a function key, the points of a curve, a library, or a language such as the diagram language D2.
 * A capital D with a digit inside a longer word, such as a hexadecimal color, is no key either. The word "decision"
 * without a number is a plain word, and so are the words phase, increment, milestone, iteration and sprint. A numbered
 * step of an algorithm is no reference unless a document is named. The kebab-case codes of diagnostics are no finding
 * keys unless a review is named.
 */
internal object ReferenceIds {

    /** A short key: one of the capital letters D, E or P followed by a number. */
    private const val KEY = """[DEP]\d+"""

    /** The key of a review finding: lowercase words or numbers joined by hyphens. */
    private const val FINDING_KEY = """[a-z0-9]+(?:-[a-z0-9]+)+"""

    /** The names of the work packages that earlier plans and comments of the project used, in capitals. */
    private val WORK_PACKAGES = listOf(
        "TEXT", "FOUNDATION", "TOKENS", "PARSE", "EDITOR-MODEL", "WORKSPACE", "LANG", "EDITOR-VIEW", "EDITOR-POPUPS",
        "WORKBENCH", "APP", "INTEGRATION",
    )

    /** The documents whose numbered rules, steps and items are referred to: plans, proposals, specifications, records. */
    private const val DOCUMENT = """(?:plan|proposal|spec(?:ification)?|record)s?"""

    /** The stages of a plan that are referred to by number: phases, increments, milestones, iterations and sprints. */
    private const val STAGE = """(?:phase|increment|milestone|iteration|sprint)s?"""

    /** A number, or a number word from zero to ten. */
    private const val NUMBER = """(?:\d+|zero|one|two|three|four|five|six|seven|eight|nine|ten)"""

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
        """(?i:\b(?:rules?|steps?|items?)\s+\d+(?:\s*(?:[-\u2013,]|and|or)\s*\d+)*\s+of\s+the\s+(?:[\w'-]+\s+){0,3}?$DOCUMENT\b)""",
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
        """(?i:\b$STAGE(?:-|\s+)$NUMBER\b)""",
    )

    /** All [patterns] as one alternative, so overlapping ids are reported once, as the longest pattern found them. */
    private val anyId = Regex(patterns.joinToString("|") { "(?:$it)" })

    /** The reference ids in [text], ordered by their offset. */
    fun findIn(text: String): List<ReferenceId> = anyId.findAll(text).map { ReferenceId(it.value, it.range.first) }.toList()
}
