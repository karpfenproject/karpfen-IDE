package io.karpfen.ide.core.workspace.document

import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * Opens, saves and closes the documents of one workspace (spec §4). Owned by the `Workspace`; all suspend functions
 * do their file I/O on the workspace's IO dispatcher and are safe to call from the UI thread.
 *
 * Loading decodes UTF-8 (malformed input → replacement characters + a WARNING), normalizes line separators to `\n`
 * (mixed separators → one INFO), and publishes those findings as the session's external diagnostics `"file"`.
 * Saving writes atomically (temp file in the same directory, then an atomic move), restores the line separator,
 * marks the save point and ignores the watch event it causes itself.
 */
public interface DocumentManager {

    /** The open documents in opening order. */
    public val openDocuments: StateFlow<List<OpenDocument>>

    /**
     * Opens [path] (or returns the already open document). Only karpfen sources can be opened
     * ([OpenResult.Unsupported] otherwise); I/O failures are [OpenResult.Failed], never exceptions.
     */
    public suspend fun open(path: Path): OpenResult

    /** The open document at [path], or null. */
    public fun get(path: Path): OpenDocument?

    /** Closes the document at [path], discarding unsaved changes (the UI asks first). No-op if it is not open. */
    public suspend fun close(path: Path)

    /** Saves the document at [path] (also when it is not dirty). */
    public suspend fun save(path: Path): SaveResult

    /** Saves every dirty document; one result per saved document, in opening order. */
    public suspend fun saveAll(): List<SaveResult>
}

/** Result of [DocumentManager.open]. */
public sealed interface OpenResult {
    /** The opened (or already open) document. */
    public data class Opened(public val document: OpenDocument) : OpenResult

    /** The file is not a karpfen source (e.g. `*.kenv.json`); the editor cannot open it in phase 1. */
    public data class Unsupported(public val path: Path) : OpenResult

    /** Reading failed; [message] is user-readable. */
    public data class Failed(public val path: Path, public val message: String) : OpenResult
}

/** Result of [DocumentManager.save]. */
public sealed interface SaveResult {
    /** The text of [version] was written to [path]. */
    public data class Saved(public val path: Path, public val version: Long) : SaveResult

    /** Writing failed (or the document is not open); [message] is user-readable. The document stays dirty. */
    public data class Failed(public val path: Path, public val message: String) : SaveResult
}
