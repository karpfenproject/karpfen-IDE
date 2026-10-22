package io.karpfen.ide.core.workspace.files

import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * Observes file-system changes below a directory. The default implementation uses
 * `java.nio.file.WatchService` with recursive registration (new directories are registered as they appear). Tests
 * use a fake that emits changes on demand, because the JDK's macOS watch service polls with multi-second delays.
 */
public fun interface FileWatcher {
    /**
     * A cold flow of changes below [root] until the collector is cancelled; paths for which [isIgnored] returns true
     * (and everything below ignored directories) are not reported. Raw events, not debounced (the workspace debounces).
     * Errors of the underlying service end the flow with a [FileChangeKind.OVERFLOW] change rather than an exception.
     */
    public fun watch(root: Path, isIgnored: (Path) -> Boolean): Flow<FileChange>
}

/** The default [FileWatcher] based on `java.nio.file.WatchService`. */
public fun FileWatcher(): FileWatcher = WatchServiceFileWatcher()

/** A change of [path] (absolute, normalized). */
public data class FileChange(public val path: Path, public val kind: FileChangeKind)

/** Kind of a [FileChange]. */
public enum class FileChangeKind {
    /** A file or directory appeared. */
    CREATED,

    /** A file's content changed. */
    MODIFIED,

    /** A file or directory disappeared. */
    DELETED,

    /** Events were lost; the consumer must rescan everything. */
    OVERFLOW,
}
