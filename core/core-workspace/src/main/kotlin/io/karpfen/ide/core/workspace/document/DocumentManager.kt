package io.karpfen.ide.core.workspace.document

import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * Opens, saves and closes the documents of one workspace. Owned by the `Workspace`; all suspend functions
 * do their file I/O on the workspace's IO dispatcher and are safe to call from the UI thread.
 *
 * Loading goes through the workspace's `SourceLoader` (UTF-8 with replacement characters + a WARNING, byte order mark
 * removed, line separators normalized to `\n` + one INFO for mixed separators) and publishes its findings as the
 * session's external diagnostics `ExternalDiagnosticSources.FILE` for the loaded snapshot. Each document's
 * `EditableText` uses `WorkspaceOptions.timeSource`. Saving writes atomically (temp file in the same directory, then an
 * atomic move), restores the line separator and the byte order mark, marks the save point and ignores the watch event
 * it causes itself.
 *
 * FILE findings follow the disk (MUST, so the IDE and `karpfen validate` agree): a successful save clears them
 * (`setExternalDiagnostics(FILE, savedSnapshot, emptyList())`; the written file is valid UTF-8 with one separator), and
 * every reload (automatic, or conflict RELOAD) replaces them with the new `LoadedSource.diagnostics` for the reloaded
 * snapshot.
 *
 * Semantic mode (MUST): every session is created with `SemanticMode.SYNTAX_ONLY` (the base
 * `WorkspaceOptions.sessionConfig` with that mode), whatever the file's role. In a workspace the project analysis is the
 * only place that converts files (context files through `DslConverter`, loose files never), and its DSL_TOOLS
 * diagnostics reach open documents as `ExternalDiagnosticSources.PROJECT`; so opening, closing or re-classifying a file
 * (e.g. it becomes loose when a descriptor appears) never changes what is reported, and the IDE and the CLI report the
 * same.
 *
 * Threading: called from the workspace's scope dispatcher (see `Workspace`, Threading); texts are only mutated there.
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

    /**
     * The file is not a karpfen source (e.g. `*.kenv.json`); the editor cannot open it, so the workbench opens it in
     * the system's default application.
     */
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
