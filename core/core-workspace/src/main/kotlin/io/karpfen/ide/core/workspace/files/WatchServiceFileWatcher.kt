package io.karpfen.ide.core.workspace.files

import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/** [FileWatcher] on `java.nio.file.WatchService`, registering directories recursively. */
internal class WatchServiceFileWatcher : FileWatcher {
    override fun watch(root: Path, isIgnored: (Path) -> Boolean): Flow<FileChange> =
        TODO("WP-WORKSPACE: recursive WatchService flow")
}
