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
 * Configuration of a [Workspace]. Tests inject test dispatchers on virtual time and a `TestTimeSource`, and they
 * usually turn [watch] off.
 *
 * @property watch whether to watch the file system. Most tests pass false and call
 *   `refresh()` instead.
 * @property watchDebounce the quiet time after the last file event before a rescan. It is 200 ms by default.
 * @property analysisDebounce the quiet time after the last change before a new analysis. It is 300 ms by default.
 * @property ignoredDirectoryNames directory names that are never scanned or watched. Hidden directories, whose name
 *   starts with `.`, are always ignored as well.
 * @property ioDispatcher runs file I/O: scanning, reading and writing.
 * @property analysisDispatcher runs the project analysis, which is CPU work.
 * @property sessionConfig the base configuration of the document sessions of opened files, such as dispatchers and
 *   debounce. The `DocumentManager` always uses it with `SemanticMode.SYNTAX_ONLY`, because the project analysis owns
 *   the semantic diagnostics.
 * @property timeSource the clock of the `EditableText` of each opened document. The text merges typing within one
 *   second into one undo step.
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
        /**
         * The folder in the project folder that holds the captured runs of the IDE. It holds log files only, so it is
         * ignored like build output.
         */
        public const val RUN_CAPTURE_DIRECTORY: String = "karpfen-runs"

        /**
         * Directories ignored by default: version control, build output, IDE settings, package caches and the
         * [RUN_CAPTURE_DIRECTORY].
         */
        public val DEFAULT_IGNORED_DIRECTORIES: Set<String> =
            setOf(".git", ".gradle", "build", "out", ".idea", ".kotlin", "node_modules", RUN_CAPTURE_DIRECTORY)
    }
}
