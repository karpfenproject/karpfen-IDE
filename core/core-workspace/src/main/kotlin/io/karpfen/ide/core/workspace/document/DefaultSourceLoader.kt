package io.karpfen.ide.core.workspace.document

import java.nio.file.Path

/** [SourceLoader] over a [TextFileStore] and `LineSeparators.normalize`. */
internal class DefaultSourceLoader(private val fileStore: TextFileStore) : SourceLoader {
    override fun load(path: Path): LoadedSource =
        TODO("WP-WORKSPACE: read through the store, normalize separators, report malformed encoding and mixed separators")
}
