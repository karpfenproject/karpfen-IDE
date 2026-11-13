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
 * Checks the form of one file of the reference in `docs/reference`. A website generator reads the numbered sections of
 * these files and builds one tree from the reference files of the three karpfen repositories, so the files keep a form
 * that the generator can rely on.
 *
 * A reference file is named `<number>-<name>.md`, such as `4.3-diagrams.md`. Its first line is the heading of its own
 * section, with one `#`, its number and its title, such as `# 4.3 Diagrams`. Every further heading is a subsection,
 * written with `##` one level below the file's own section or with `###` two levels below, and it starts with its full
 * number, such as `## 4.3.2 Reading a statechart`. A number has at most four parts. The numbers run in the order of
 * the file without gaps or repeats: a heading either goes one level deeper with the number 1, or counts on at its own
 * level or at a level above. A heading ends without a dot or a colon. A chapter head whose sections are files of their
 * own, such as `4-ide.md`, has no subheadings, which the rules allow.
 *
 * Every fenced code block names its language, one of [LANGUAGES]. Outside code blocks the text does without bold, the
 * em dash, the en dash, the middle dot and arrows, also the ASCII arrows `->`, `<-` and `=>` outside code in backticks.
 *
 * The rules are line based. A line that starts with three backticks, after any indentation, opens or closes a fenced
 * code block. Headings inside a code block are text of the block.
 */
internal object ReferenceMarkdown {

    /** The languages a fenced code block may name. `text` stands for program output. */
    val LANGUAGES: Set<String> = setOf("kmeta", "kmodel", "kstates", "json", "bash", "python", "kotlin", "http", "text")

    /** The deepest number of a heading, in parts. */
    private const val MAX_PARTS = 4

    /** The name of a reference file: its number, a hyphen and a name in lower case, then `.md`. */
    private val FILE_NAME = Regex("""^(\d+(?:\.\d+)*)-[a-z0-9-]+\.md$""")

    /** A heading line: its hashes and its text. */
    private val HEADING = Regex("""^(#+)\s+(.*)$""")

    /** The text of a numbered heading: the number, one blank and the title. */
    private val NUMBERED = Regex("""^(\d+(?:\.\d+)*) (\S.*)$""")

    /** A line that opens or closes a fenced code block, with the language that follows the backticks. */
    private val FENCE = Regex("""^\s*```(.*)$""")

    /** A span of code in backticks within a line. */
    private val INLINE_CODE = Regex("""`[^`]*`""")

    /** Bold written with two asterisks or two underscores. */
    private val BOLD = Regex("""\*\*|__""")

    /** The ASCII arrows. */
    private val ASCII_ARROW = Regex("""->|<-|=>""")

    /** The characters that the text outside code blocks does without, by their names. */
    private val CHARACTERS: Map<Char, String> = mapOf(
        '—' to "an em dash",
        '–' to "an en dash",
        '·' to "a middle dot",
    )

    /** The Unicode block of arrows. */
    private val ARROWS: CharRange = '←'..'⇿'

    /** The number in the name of the reference file [fileName], or null if the name is not that of a reference file. */
    fun numberOf(fileName: String): String? = FILE_NAME.matchEntire(fileName)?.groupValues?.get(1)

    /**
     * What is wrong with the reference file named [fileName] whose text is [text], one message per violation with
     * its line. An empty list means the file keeps the form.
     */
    fun violationsIn(fileName: String, text: String): List<String> {
        val number = numberOf(fileName) ?: return listOf("$fileName is not named <number>-<name>.md")
        val lines = text.lines()
        val violations = mutableListOf<String>()
        val first = lines.firstOrNull().orEmpty()
        if (HEADING.matchEntire(first)?.groupValues?.get(1) != "#" || numberedTitle(first.removePrefix("#").trim())?.first != number) {
            violations += "line 1: the file must start with the heading '# $number <title>'"
        }
        var previous = number.split('.').map(String::toInt)
        var inCode = false
        lines.forEachIndexed { index, line ->
            val at = "line ${index + 1}"
            val fence = FENCE.matchEntire(line)
            when {
                fence != null -> {
                    if (!inCode && fence.groupValues[1].trim() !in LANGUAGES) {
                        violations += "$at: a code block must name its language, one of ${LANGUAGES.sorted().joinToString()}"
                    }
                    inCode = !inCode
                }
                inCode -> Unit
                else -> {
                    if (index > 0) headingViolation(line, number, previous)?.let { violations += "$at: $it" }
                    if (index > 0) HEADING.matchEntire(line)?.let { heading -> numberedTitle(heading.groupValues[2])?.let { previous = parts(it.first) } }
                    violations += proseViolations(line).map { "$at: $it" }
                }
            }
        }
        if (inCode) violations += "a code block is not closed"
        return violations
    }

    /** The number and the title of the heading text [text], or null if it does not start with a number. */
    private fun numberedTitle(text: String): Pair<String, String>? =
        NUMBERED.matchEntire(text)?.let { it.groupValues[1] to it.groupValues[2] }

    /** The parts of the section number [number]. */
    private fun parts(number: String): List<Int> = number.split('.').map(String::toInt)

    /**
     * What is wrong with [line] as a heading of a subsection of the section [number], given the number [previous] of
     * the heading before it, or null if the line is no heading or a correct one.
     */
    private fun headingViolation(line: String, number: String, previous: List<Int>): String? {
        val heading = HEADING.matchEntire(line) ?: return null
        val level = heading.groupValues[1].length
        val text = heading.groupValues[2].trim()
        val (own, title) = numberedTitle(text) ?: return "the heading '$text' has no number"
        val section = parts(number)
        val current = parts(own)
        return when {
            level != 2 && level != 3 -> "a subsection is written with ## or ###, not with ${heading.groupValues[1]}"
            current.size > MAX_PARTS -> "the number $own has more than $MAX_PARTS parts"
            current.size != section.size + level - 1 -> "the heading $own needs ${"#".repeat(current.size - section.size + 1)}"
            current.take(section.size) != section -> "the number $own is not within the section $number"
            !follows(current, previous) -> "the number $own does not follow ${previous.joinToString(".")}"
            title.endsWith(".") || title.endsWith(":") -> "the heading $own ends with a dot or a colon"
            else -> null
        }
    }

    /**
     * True if the section [current] may follow the section [previous]: as its first subsection, or as the next
     * section at its own level or at a level above.
     */
    private fun follows(current: List<Int>, previous: List<Int>): Boolean = when {
        current.size == previous.size + 1 -> current.dropLast(1) == previous && current.last() == 1
        current.size <= previous.size -> current.dropLast(1) == previous.take(current.size - 1) && current.last() == previous[current.size - 1] + 1
        else -> false
    }

    /** What [line] of prose outside a code block has that the reference does without. */
    private fun proseViolations(line: String): List<String> {
        val prose = INLINE_CODE.replace(line, "")
        return buildList {
            if (BOLD.containsMatchIn(prose)) add("bold is not used")
            ASCII_ARROW.find(prose)?.let { add("the arrow '${it.value}' stands outside code in backticks") }
            line.forEach { char ->
                CHARACTERS[char]?.let { add("$it is not used outside code blocks") }
                if (char in ARROWS) add("an arrow U+%04X is not used outside code blocks".format(char.code))
            }
        }
    }
}
