package io.karpfen.ide.core.workspace.document

import java.nio.file.Path

/** [TextFileStore] on `java.nio.file`, writing through a temp file and an atomic move. */
internal class AtomicTextFileStore : TextFileStore {
    override fun read(path: Path): DecodedText = TODO("WP-WORKSPACE: UTF-8 decode with replacement, BOM detection")

    override fun write(path: Path, content: String, withBom: Boolean): Unit = TODO("WP-WORKSPACE: atomic write")
}
