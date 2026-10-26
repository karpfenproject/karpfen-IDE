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
package io.karpfen.ide.core.text

/**
 * A line separator as found in files on disk. In memory every document uses [LF] only; the dominant original separator
 * is remembered per document and restored on save, and a file with mixed separators is normalized and reported once.
 *
 * The entries are declared in the tie-breaking order of [LineSeparators.normalize] (LF, CRLF, CR): when two
 * separators occur equally often, the one declared first is the dominant one.
 *
 * @property chars the separator's characters as written to disk.
 * @property displayName the short name the status bar shows.
 */
public enum class LineSeparator(public val chars: String, public val displayName: String) {
    /** `\n` (Unix, macOS). */
    LF("\n", "LF"),

    /** `\r\n` (Windows). */
    CRLF("\r\n", "CRLF"),

    /** `\r` (classic Mac OS). */
    CR("\r", "CR"),
}

/**
 * Result of normalizing raw file text to `\n` separators.
 *
 * @property text the text with every `\r\n` and lone `\r` replaced by `\n`.
 * @property dominant the separator that occurred most often in the raw text ([LineSeparator.LF] for text without
 *   separators; ties are broken in the order LF, CRLF, CR).
 * @property mixed true if the raw text contained more than one kind of separator (reported once as an INFO
 *   diagnostic by the workspace).
 */
public data class NormalizedText(
    public val text: String,
    public val dominant: LineSeparator,
    public val mixed: Boolean,
)

/** Pure functions converting between file text and the `\n`-only in-memory form. */
public object LineSeparators {

    /** Normalizes [raw] to `\n` separators and reports the dominant separator (see [NormalizedText]). */
    public fun normalize(raw: String): NormalizedText {
        if (raw.indexOf('\r') < 0) {
            return NormalizedText(raw, LineSeparator.LF, mixed = false)
        }
        val counts = IntArray(LineSeparator.entries.size)
        val normalized = StringBuilder(raw.length)
        var offset = 0
        while (offset < raw.length) {
            val char = raw[offset]
            val separator = when {
                char == '\n' -> LineSeparator.LF
                char != '\r' -> null
                offset + 1 < raw.length && raw[offset + 1] == '\n' -> LineSeparator.CRLF
                else -> LineSeparator.CR
            }
            if (separator == null) {
                normalized.append(char)
            } else {
                counts[separator.ordinal]++
                normalized.append('\n')
            }
            offset += separator?.chars?.length ?: 1
        }
        val kinds = counts.count { it > 0 }
        return NormalizedText(normalized.toString(), dominantSeparator(counts), mixed = kinds > 1)
    }

    /**
     * The separator with the highest of [counts] (indexed by ordinal). `maxBy` keeps the first of equal maxima, so
     * ties go to the separator declared first in [LineSeparator] (LF, then CRLF, then CR).
     */
    private fun dominantSeparator(counts: IntArray): LineSeparator = LineSeparator.entries.maxBy { counts[it.ordinal] }

    /**
     * Converts `\n`-normalized [text] back to file form by replacing every `\n` with [separator]. For text without
     * mixed separators, `restore(normalize(raw).text, normalize(raw).dominant) == raw`.
     */
    public fun restore(text: String, separator: LineSeparator): String =
        if (separator == LineSeparator.LF) text else text.replace("\n", separator.chars)
}
