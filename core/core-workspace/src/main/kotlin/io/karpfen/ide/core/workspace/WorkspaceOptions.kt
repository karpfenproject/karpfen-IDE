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
package io.karpfen.ide.core.workspace

import io.karpfen.ide.core.document.session.DocumentSessionConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Configuration of a [Workspace]. Tests inject test dispatchers (virtual time), a `TestTimeSource` and usually disable
 * [watch].
 *
 * @property watch whether to watch the file system (the CLI and most tests pass false and call `refresh()`).
 * @property watchDebounce quiet time after the last file event before rescanning (200 ms by default).
 * @property analysisDebounce quiet time after the last change before re-analysis (300 ms by default).
 * @property ignoredDirectoryNames directory names never scanned or watched; hidden directories (name starting with
 *   `.`) are always ignored as well.
 * @property ioDispatcher file I/O (scan, read, write).
 * @property analysisDispatcher project analysis (CPU).
 * @property sessionConfig base configuration of the document sessions of opened files (dispatchers, debounce); the
 *   `DocumentManager` always uses it with `SemanticMode.SYNTAX_ONLY` (see `DocumentManager`, Semantic mode).
 * @property timeSource clock of the opened documents' `EditableText`s, which merge typing within one second into one
 *   undo step.
 */
public data class WorkspaceOptions(
    public val watch: Boolean = true,
    public val watchDebounce: Duration = 200.milliseconds,
    public val analysisDebounce: Duration = 300.milliseconds,
    public val ignoredDirectoryNames: Set<String> = DEFAULT_IGNORED_DIRECTORIES,
    public val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    public val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
    public val sessionConfig: DocumentSessionConfig = DocumentSessionConfig(),
    public val timeSource: TimeSource = TimeSource.Monotonic,
) {
    init {
        require(!watchDebounce.isNegative() && !analysisDebounce.isNegative()) { "debounce durations must not be negative" }
    }

    public companion object {
        /** Directories ignored by default: version control, build output, IDE settings and package caches. */
        public val DEFAULT_IGNORED_DIRECTORIES: Set<String> =
            setOf(".git", ".gradle", "build", "out", ".idea", ".kotlin", "node_modules")
    }
}
