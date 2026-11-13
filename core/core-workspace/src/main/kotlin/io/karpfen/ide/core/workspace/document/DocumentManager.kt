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
package io.karpfen.ide.core.workspace.document

import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * Opens, saves and closes the documents of one workspace. The `Workspace` owns it. All suspend functions do their file
 * I/O on the workspace's IO dispatcher and are safe to call from the UI thread.
 *
 * Loading goes through the workspace's `SourceLoader`. It decodes UTF-8 and replaces invalid bytes with replacement
 * characters and a WARNING. It removes a byte order mark. It normalizes line separators to `\n` and adds one INFO for
 * mixed separators. The findings are published as the session's external diagnostics
 * `ExternalDiagnosticSources.FILE` for the loaded snapshot. The `EditableText` of each document uses
 * `WorkspaceOptions.timeSource`. Saving writes atomically, first to a temp file in the same directory and then with an
 * atomic move. It restores the line separator and the byte order mark, marks the save point and ignores the watch event
 * it causes itself.
 *
 * FILE findings always follow the disk, so the editor and the validation of the project agree. A successful save clears them with
 * `setExternalDiagnostics(FILE, savedSnapshot, emptyList())`, because the written file is valid UTF-8 with one kind of
 * line separator. Every reload replaces them with the new `LoadedSource.diagnostics` for the reloaded snapshot. That
 * holds for an automatic reload and for the reload that resolves a conflict.
 *
 * Every session is created with `SemanticMode.SYNTAX_ONLY`, whatever the role of the file. The session gets the base
 * `WorkspaceOptions.sessionConfig` with that mode. In a workspace the project analysis is the only place where
 * dsl-tools reads files. It reads context files and loose state machines through `DslReader`. Its DSL_TOOLS
 * diagnostics reach the open documents as `ExternalDiagnosticSources.PROJECT`. So opening, closing or reclassifying a
 * file never changes what is reported, and the editor and the validation of the project report the same. A file is
 * reclassified, for example, when it becomes loose because a descriptor appears.
 *
 * Call it from the dispatcher of the workspace's scope, as described on `Workspace`. Texts are only changed there.
 */
public interface DocumentManager {

    /** The open documents in opening order. */
    public val openDocuments: StateFlow<List<OpenDocument>>

    /**
     * Opens [path], or returns the document if it is already open. Only files of a registered karpfen language can be
     * opened. These are the modeling languages and the environment descriptors. Any other file gives
     * [OpenResult.Unsupported]. I/O failures give [OpenResult.Failed] and never throw.
     */
    public suspend fun open(path: Path): OpenResult

    /** The open document at [path], or null. */
    public fun get(path: Path): OpenDocument?

    /**
     * Closes the document at [path] and discards unsaved changes. The UI asks the user first. It does nothing if the
     * document is not open.
     */
    public suspend fun close(path: Path)

    /** Saves the document at [path], also when it is not dirty. */
    public suspend fun save(path: Path): SaveResult

    /** Saves every dirty document. It returns one result per saved document, in opening order. */
    public suspend fun saveAll(): List<SaveResult>
}

/** Result of [DocumentManager.open]. */
public sealed interface OpenResult {
    /** The document that was opened or was already open. */
    public data class Opened(public val document: OpenDocument) : OpenResult

    /**
     * The file is in no karpfen language, such as a README or a bridge configuration. The editor cannot open it. The
     * workbench opens a bridge configuration in its form and anything else in the system's default application.
     */
    public data class Unsupported(public val path: Path) : OpenResult

    /** Reading failed. [message] is meant for the user. */
    public data class Failed(public val path: Path, public val message: String) : OpenResult
}

/** Result of [DocumentManager.save]. */
public sealed interface SaveResult {
    /** The text of [version] was written to [path]. */
    public data class Saved(public val path: Path, public val version: Long) : SaveResult

    /**
     * Writing failed, or the document is not open. [message] is meant for the user. The document stays dirty.
     */
    public data class Failed(public val path: Path, public val message: String) : SaveResult
}
