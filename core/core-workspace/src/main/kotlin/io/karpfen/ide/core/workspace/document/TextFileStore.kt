package io.karpfen.ide.core.workspace.document

import java.nio.file.Path

/**
 * Reads and writes text files for the [DocumentManager] (UTF-8). Blocking; called on an IO dispatcher. Tests may
 * replace it with an in-memory fake.
 */
public interface TextFileStore {

    /**
     * Reads [path] as UTF-8; malformed input is replaced (U+FFFD) and flagged, never rejected.
     * @throws java.io.IOException if the file cannot be read.
     */
    public fun read(path: Path): DecodedText

    /**
     * Writes [content] (already in file form, i.e. with the original line separators) to [path] atomically: a temp
     * file in the same directory is moved over the target (`ATOMIC_MOVE`, falling back to `REPLACE_EXISTING` where
     * atomic moves are unsupported).
     * @throws java.io.IOException if the file cannot be written; the target is then unchanged.
     */
    public fun write(path: Path, content: String)
}

/** The file-system [TextFileStore]. */
public fun TextFileStore(): TextFileStore = AtomicTextFileStore()

/**
 * Text read from a file.
 *
 * @property content the decoded text with its original line separators.
 * @property malformed true if the bytes were not valid UTF-8.
 */
public data class DecodedText(public val content: String, public val malformed: Boolean)
