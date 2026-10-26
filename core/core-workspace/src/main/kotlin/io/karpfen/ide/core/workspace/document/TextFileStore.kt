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
package io.karpfen.ide.core.workspace.document

import java.nio.file.Path

/**
 * Reads and writes text files for the [DocumentManager] (UTF-8). Blocking; called on an IO dispatcher. Tests may
 * replace it with an in-memory fake.
 */
public interface TextFileStore {

    /**
     * Reads [path] as UTF-8; malformed input is replaced (U+FFFD) and flagged, never rejected. A leading UTF-8 byte
     * order mark is removed from the content and reported as [DecodedText.hasBom] (otherwise U+FEFF would be a lexer
     * error on line 1).
     * @throws java.io.IOException if the file cannot be read.
     */
    public fun read(path: Path): DecodedText

    /**
     * Writes [content] (already in file form, i.e. with the original line separators) to [path] atomically: a temp
     * file in the same directory is moved over the target (`ATOMIC_MOVE`, falling back to `REPLACE_EXISTING` where
     * atomic moves are unsupported). With [withBom] a UTF-8 byte order mark is written first, restoring a file that had
     * one (`read` then `write` with the same flag round-trips the bytes of valid UTF-8 files).
     * @throws java.io.IOException if the file cannot be written; the target is then unchanged.
     */
    public fun write(path: Path, content: String, withBom: Boolean = false)

    /**
     * What [read] returns for the file [write] leaves with [content] and [withBom], computed without I/O: a save names
     * the disk state it leaves with it. The default is the UTF-8 rule of the file-system store (a leading U+FEFF of
     * [content] reads back as a byte order mark, an unpaired surrogate as `?`); a store with other bytes overrides it.
     */
    public fun readBack(content: String, withBom: Boolean = false): DecodedText = Utf8Text.decode(Utf8Text.encode(content, withBom))
}

/** The file-system [TextFileStore]. */
public fun TextFileStore(): TextFileStore = AtomicTextFileStore()

/**
 * Text read from a file.
 *
 * @property content the decoded text with its original line separators, without a byte order mark.
 * @property malformed true if the bytes were not valid UTF-8.
 * @property hasBom true if the file started with a UTF-8 byte order mark (removed from [content]).
 */
public data class DecodedText(public val content: String, public val malformed: Boolean, public val hasBom: Boolean = false)
