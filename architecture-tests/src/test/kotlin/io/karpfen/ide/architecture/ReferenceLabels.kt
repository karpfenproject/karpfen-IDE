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
 * Checks the stars in one line of prose of the reference, outside fenced code blocks. The reference sets a name that
 * the IDE shows on screen, such as a menu entry, a button or a tool window, in single stars, and the website generator
 * prints such a label in italics. The generator reads only one form of label, and every other star would show up as a
 * literal star on the website and in the PDF.
 *
 * A label in that form looks like `*Fit*`, `(*Fit*)` or `*Run > Start Runtime*`. Its opening star stands at the start
 * of the line or of a table cell, or behind a blank or an opening bracket, and a character that is not blank follows
 * it. Its closing star stands right behind a character that is not blank, on the same line. A label holds no code in
 * backticks. A label that is also a link has its stars outside the link, as in `*[Fit](4.3-diagrams.md)*`.
 *
 * Stars inside code in backticks are code and are not looked at. Two stars in a row are bold, which [ReferenceMarkdown]
 * reports on its own, so they are passed over here. A heading holds no star at all, because the text of a heading is
 * the anchor that links point to.
 */
internal object ReferenceLabels {

    /** A heading line, which starts with one or more hashes and a blank. */
    private val HEADING = Regex("""^#+\s""")

    /** The characters behind which a label may open, besides the start of a line or table cell. */
    private const val OPENERS = " ([|"

    /**
     * What is wrong with the stars of [line], one message per violation. An empty list means that every star is in
     * order.
     */
    fun violationsIn(line: String): List<String> =
        if (HEADING.containsMatchIn(line)) {
            listOfNotNull("a heading holds no star".takeIf { '*' in line })
        } else {
            LabelScan(line).violations()
        }

    /**
     * One pass over [line] from left to right. It skips code in backticks, pairs every single star outside code with
     * the next one as the two ends of a label and records what breaks the form of a label.
     */
    private class LabelScan(private val line: String) {

        /** The violations found so far. */
        private val found = mutableListOf<String>()

        /** The index of the opening star of the label that is open at the current position, or null. */
        private var openedAt: Int? = null

        /** True while the open label began with a misplaced star, which has been reported already. */
        private var misplaced = false

        /** The violations of the whole line. */
        fun violations(): List<String> {
            var index = 0
            while (index < line.length) {
                index = when {
                    line[index] == '`' -> afterCode(index)
                    line.startsWith("**", index) -> index + 2
                    line[index] == '*' -> afterStar(index)
                    else -> index + 1
                }
            }
            if (openedAt != null && !misplaced) found += "a label in stars must close on the line where it opens"
            return found
        }

        /**
         * Skips the code in backticks that starts at [start] and returns the index behind it. A backtick without a
         * partner ends the scan, since the rest of the line is no prose that a label could use.
         */
        private fun afterCode(start: Int): Int {
            if (openedAt != null) found += "a label in stars holds no code in backticks"
            val end = line.indexOf('`', start + 1)
            return if (end < 0) line.length else end + 1
        }

        /** Takes the single star at [index] as the start or the end of a label and returns the index behind it. */
        private fun afterStar(index: Int): Int {
            val opened = openedAt
            if (opened == null) open(index) else close(opened, index)
            return index + 1
        }

        /**
         * Opens a label at the star at [index] and records it if the star does not stand where a label may open. The
         * label is opened in either case, so that the next star is taken as its end and one misplaced star gives one
         * message.
         */
        private fun open(index: Int) {
            openedAt = index
            val placed = line.substring(0, index).isBlank() || line[index - 1] in OPENERS
            val followed = index + 1 < line.length && !line[index + 1].isWhitespace()
            misplaced = !placed || !followed
            if (misplaced) {
                found += "the star at column ${index + 1} opens no label: an opening star stands at the start of the " +
                    "line or of a table cell, or behind a blank or an opening bracket, with text right behind it"
            }
        }

        /** Closes the label that opened at [opened] with the star at [index], and records what is wrong with it. */
        private fun close(opened: Int, index: Int) {
            openedAt = null
            if (line[index - 1].isWhitespace()) {
                found += "the star at column ${index + 1} closes no label: a closing star has text right before it"
            }
            if (opened > 0 && line[opened - 1] == '[' && line.startsWith("](", index + 1)) {
                found += "the label at column ${opened + 1} is a link: its stars stand outside the link, as in *[label](link)*"
            }
        }
    }
}
