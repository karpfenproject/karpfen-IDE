package io.karpfen.ide.core.workspace.document

import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path
import kotlin.time.TimeSource

/**
 * The document side of a workspace: the public [DocumentManager] plus the workspace's debounced file changes. The
 * workspace depends on this interface (not on [DefaultDocumentManager]), so its watch pipeline can be tested with a
 * fake.
 */
internal interface WorkspaceDocuments : DocumentManager {
    /** Called by the workspace for every debounced change of a file: reload, conflict, or nothing (self-induced). */
    suspend fun onFileChanged(path: Path, deleted: Boolean)

    /** Closes every open document (unsaved changes are discarded); used by `Workspace.close`. */
    fun closeAll()
}

/**
 * Default [WorkspaceDocuments] (reload / conflict handling, ignoring self-induced events after a save; FILE findings
 * and the semantic mode as documented on [DocumentManager]).
 *
 * @param scope the workspace's scope; its dispatcher is the single writer of every document's text.
 * @param sourceLoader loads files for opening and reloading.
 * @param fileStore writes files when saving.
 * @param sessionConfig base configuration of every session; used with `semanticMode = SemanticMode.SYNTAX_ONLY`.
 * @param timeSource clock of every document's `EditableText`.
 */
internal class DefaultDocumentManager(
    private val scope: CoroutineScope,
    private val languages: LanguageRegistry,
    private val sourceLoader: SourceLoader,
    private val fileStore: TextFileStore,
    private val ioDispatcher: CoroutineDispatcher,
    private val sessionConfig: DocumentSessionConfig,
    private val timeSource: TimeSource,
) : WorkspaceDocuments {

    override val openDocuments: StateFlow<List<OpenDocument>>
        get() = TODO("WP-WORKSPACE: open documents")

    override suspend fun open(path: Path): OpenResult = TODO("WP-WORKSPACE: open")

    override fun get(path: Path): OpenDocument? = TODO("WP-WORKSPACE: get")

    override suspend fun close(path: Path): Unit = TODO("WP-WORKSPACE: close")

    override suspend fun save(path: Path): SaveResult = TODO("WP-WORKSPACE: atomic save")

    override suspend fun saveAll(): List<SaveResult> = TODO("WP-WORKSPACE: save all")

    override suspend fun onFileChanged(path: Path, deleted: Boolean): Unit = TODO("WP-WORKSPACE: external changes")

    override fun closeAll(): Unit = TODO("WP-WORKSPACE: close every document")
}
