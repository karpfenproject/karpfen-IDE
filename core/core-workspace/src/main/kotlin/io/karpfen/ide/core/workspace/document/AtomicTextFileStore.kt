package io.karpfen.ide.core.workspace.document

import java.nio.file.Path

/** [TextFileStore] on `java.nio.file`, writing through a temp file and an atomic move. */
internal class AtomicTextFileStore : TextFileStore {
    override fun read(path: Path): DecodedText = TODO("WP-WORKSPACE: UTF-8 decode with replacement")

    override fun write(path: Path, content: String): Unit = TODO("WP-WORKSPACE: atomic write")
}
