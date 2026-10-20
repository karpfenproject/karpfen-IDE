package io.karpfen.ide.core.text

/**
 * A line separator as found in files on disk. In memory every document uses [LF] only (spec §1); the original
 * separator is remembered per document and restored on save.
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
    public fun normalize(raw: String): NormalizedText = TODO("WP-TEXT: detect the dominant separator and normalize to \\n")

    /**
     * Converts `\n`-normalized [text] back to file form by replacing every `\n` with [separator]. For text without
     * mixed separators, `restore(normalize(raw).text, normalize(raw).dominant) == raw`.
     */
    public fun restore(text: String, separator: LineSeparator): String =
        TODO("WP-TEXT: replace every \\n by the separator characters")
}
