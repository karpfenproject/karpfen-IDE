package io.karpfen.ide.core.workspace

import io.karpfen.ide.core.document.session.DocumentSessionConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Configuration of a [Workspace]. Tests inject test dispatchers (virtual time) and usually disable [watch].
 *
 * @property watch whether to watch the file system (the CLI and most tests pass false and call `refresh()`).
 * @property watchDebounce quiet time after the last file event before rescanning (spec §4: 200 ms).
 * @property analysisDebounce quiet time after the last change before re-analysis (spec §4: 300 ms).
 * @property ignoredDirectoryNames directory names never scanned or watched; hidden directories (name starting with
 *   `.`) are always ignored as well.
 * @property ioDispatcher file I/O (scan, read, write).
 * @property analysisDispatcher project analysis (CPU).
 * @property sessionConfig configuration of the document sessions of opened files.
 */
public data class WorkspaceOptions(
    public val watch: Boolean = true,
    public val watchDebounce: Duration = 200.milliseconds,
    public val analysisDebounce: Duration = 300.milliseconds,
    public val ignoredDirectoryNames: Set<String> = DEFAULT_IGNORED_DIRECTORIES,
    public val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    public val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
    public val sessionConfig: DocumentSessionConfig = DocumentSessionConfig(),
) {
    init {
        require(!watchDebounce.isNegative() && !analysisDebounce.isNegative()) { "debounce durations must not be negative" }
    }

    public companion object {
        /** Directories ignored by default (spec §4). */
        public val DEFAULT_IGNORED_DIRECTORIES: Set<String> =
            setOf(".git", ".gradle", "build", "out", ".idea", ".kotlin", "node_modules")
    }
}
