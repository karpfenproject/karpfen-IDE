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
package io.karpfen.ide.core.workspace.files

import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * Observes file-system changes below a directory. The default implementation uses `java.nio.file.WatchService` with
 * recursive registration, so new directories are registered as they appear. Tests use a fake that emits changes on
 * demand, because the JDK's watch service on macOS polls with delays of several seconds.
 */
public fun interface FileWatcher {
    /**
     * A cold flow of changes below [root] until the collector is cancelled. Paths for which [isIgnored] returns true,
     * and everything below ignored directories, are not reported. The events are raw and not debounced, because the
     * workspace debounces them. Errors of the underlying service end the flow with a [FileChangeKind.OVERFLOW] change
     * instead of an exception.
     */
    public fun watch(root: Path, isIgnored: (Path) -> Boolean): Flow<FileChange>
}

/** The default [FileWatcher], based on `java.nio.file.WatchService`. */
public fun FileWatcher(): FileWatcher = WatchServiceFileWatcher()

/** A change of [path], which is absolute and normalized. */
public data class FileChange(public val path: Path, public val kind: FileChangeKind)

/** The kind of a [FileChange]. */
public enum class FileChangeKind {
    /** A file or directory appeared. */
    CREATED,

    /** A file's content changed. */
    MODIFIED,

    /** A file or directory disappeared. */
    DELETED,

    /** Events were lost. The consumer has to rescan everything. */
    OVERFLOW,
}
