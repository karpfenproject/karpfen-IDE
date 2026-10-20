package io.karpfen.ide.core.workspace.document

import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * Default [DocumentManager]. Besides the public API it receives the workspace's debounced file changes
 * (reload / conflict handling, ignoring self-induced events after a save).
 *
 * @param sessionConfig base configuration of every session; `EVENTS.kmeta` sessions use `SemanticMode.SYNTAX_ONLY`.
 */
internal class DefaultDocumentManager(
    private val scope: CoroutineScope,
    private val languages: LanguageRegistry,
    private val fileStore: TextFileStore,
    private val ioDispatcher: CoroutineDispatcher,
    private val sessionConfig: DocumentSessionConfig,
) : DocumentManager {

    override val openDocuments: StateFlow<List<OpenDocument>>
        get() = TODO("WP-WORKSPACE: open documents")

    override suspend fun open(path: Path): OpenResult = TODO("WP-WORKSPACE: open")

    override fun get(path: Path): OpenDocument? = TODO("WP-WORKSPACE: get")

    override suspend fun close(path: Path): Unit = TODO("WP-WORKSPACE: close")

    override suspend fun save(path: Path): SaveResult = TODO("WP-WORKSPACE: atomic save")

    override suspend fun saveAll(): List<SaveResult> = TODO("WP-WORKSPACE: save all")

    /** Called by the workspace for every debounced change of a file (reload or conflict). */
    suspend fun onFileChanged(path: Path, deleted: Boolean): Unit = TODO("WP-WORKSPACE: external changes")
}
