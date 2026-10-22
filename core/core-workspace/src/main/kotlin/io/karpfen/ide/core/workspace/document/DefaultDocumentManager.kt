package io.karpfen.ide.core.workspace.document

import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.workspace.files.FileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
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
 * The registry of open documents; the work is delegated to single-purpose collaborators: [OpenDocumentFactory]
 * (sessions, FILE findings), [DocumentSaver] (atomic save, save point) and [DiskSynchronizer] (external changes and
 * conflicts). Every mutation of a document runs on the [SingleWriter] of [scope].
 *
 * @param scope the workspace's scope; its dispatcher is the single writer of every document's text.
 * @param sourceLoader loads files for opening and reloading.
 * @param fileStore writes files when saving.
 * @param sessionConfig base configuration of every session; used with `semanticMode = SemanticMode.SYNTAX_ONLY`.
 * @param timeSource clock of every document's `EditableText`.
 */
internal class DefaultDocumentManager(
    scope: CoroutineScope,
    private val languages: LanguageRegistry,
    private val sourceLoader: SourceLoader,
    fileStore: TextFileStore,
    private val ioDispatcher: CoroutineDispatcher,
    sessionConfig: DocumentSessionConfig,
    timeSource: TimeSource,
) : WorkspaceDocuments {

    private val writer = SingleWriter(scope)
    private val synchronizer = DiskSynchronizer(sourceLoader, ioDispatcher, writer)
    private val saver = DocumentSaver(fileStore, sourceLoader, ioDispatcher, writer)
    private val factory = OpenDocumentFactory(scope, sessionConfig, timeSource, synchronizer)
    private val documents = MutableStateFlow<List<DefaultOpenDocument>>(emptyList())

    override val openDocuments: StateFlow<List<OpenDocument>> = documents.asStateFlow()

    /**
     * Loads the file on the IO dispatcher and registers its document on the single writer. When another open of the same
     * file completed while this one was loading, that document is returned and the second load is dropped.
     */
    override suspend fun open(path: Path): OpenResult {
        val normalized = path.toAbsolutePath().normalize()
        find(normalized)?.let { return OpenResult.Opened(it) }
        val name = normalized.fileName?.toString() ?: return OpenResult.Unsupported(normalized)
        val kind = FileKind.of(name)
        val support = languages.supportFor(name)
        if (!kind.isKarpfenSource || support == null) return OpenResult.Unsupported(normalized)
        val loaded = try {
            withContext(ioDispatcher) { sourceLoader.load(normalized) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            logger.info("Cannot open {}: {}", normalized, e.toString())
            return OpenResult.Failed(normalized, "Cannot open ${describeIoFailure(normalized, e)}")
        }
        return writer.run {
            val document = find(normalized) ?: factory.create(loaded, kind, support).also { created ->
                documents.update { it + created }
            }
            OpenResult.Opened(document)
        }
    }

    override fun get(path: Path): OpenDocument? = find(path.toAbsolutePath().normalize())

    override suspend fun close(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        writer.run {
            val document = find(normalized) ?: return@run
            documents.update { open -> open.filterNot { it === document } }
            document.close()
        }
    }

    override suspend fun save(path: Path): SaveResult {
        val normalized = path.toAbsolutePath().normalize()
        val document = find(normalized) ?: return SaveResult.Failed(normalized, "${normalized.fileName} is not open")
        return saver.save(document)
    }

    override suspend fun saveAll(): List<SaveResult> =
        documents.value.filter { it.isDirtyNow }.map { saver.save(it) }

    override suspend fun onFileChanged(path: Path, deleted: Boolean) {
        val document = find(path.toAbsolutePath().normalize()) ?: return
        synchronizer.onFileChanged(document, deleted)
    }

    override fun closeAll() {
        documents.getAndUpdate { emptyList() }.forEach { it.close() }
    }

    /** The open document at the [normalized] path, or null. */
    private fun find(normalized: Path): DefaultOpenDocument? = documents.value.firstOrNull { it.path == normalized }

    private companion object {
        private val logger = LoggerFactory.getLogger(DefaultDocumentManager::class.java)
    }
}
