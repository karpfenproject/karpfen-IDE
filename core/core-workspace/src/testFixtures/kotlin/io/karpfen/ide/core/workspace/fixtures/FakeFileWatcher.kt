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
package io.karpfen.ide.core.workspace.fixtures

import io.karpfen.ide.core.workspace.files.FileChange
import io.karpfen.ide.core.workspace.files.FileChangeKind
import io.karpfen.ide.core.workspace.files.FileWatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterNot
import java.nio.file.Path

/**
 * A [FileWatcher] that emits the changes a test hands it. Tests use it because the real watch service polls slowly on
 * macOS.
 */
public class FakeFileWatcher : FileWatcher {
    private val changes = MutableSharedFlow<FileChange>(extraBufferCapacity = 1024)

    /** The root of the latest [watch] call, null before the first one. */
    public var watchedRoot: Path? = null
        private set

    override fun watch(root: Path, isIgnored: (Path) -> Boolean): Flow<FileChange> {
        watchedRoot = root
        return changes.filterNot { isIgnored(it.path) }
    }

    /** Reports a change of [path]. */
    public fun report(path: Path, kind: FileChangeKind = FileChangeKind.MODIFIED) {
        check(changes.tryEmit(FileChange(path.toAbsolutePath().normalize(), kind)))
    }
}
