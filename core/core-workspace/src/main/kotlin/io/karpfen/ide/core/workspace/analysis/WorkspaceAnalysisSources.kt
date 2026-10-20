package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.document.DocumentManager
import io.karpfen.ide.core.workspace.document.SourceLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * The [AnalysisSources] of a workspace (spec §4: editor text over disk text): an open document is analyzed with its
 * session's current snapshot, its tokens from `DocumentSession.tokensFor` and its parse when the parse has that
 * version; any other file is loaded through the workspace's [sourceLoader] on [ioDispatcher] into a fresh snapshot at
 * version 0 (a lineage of its own) with the loading findings. Unreadable files are null. Thread-safe: it only reads
 * snapshots and state flows, so the analysis may call it from its own dispatcher.
 */
internal class WorkspaceAnalysisSources(
    private val documents: DocumentManager,
    private val sourceLoader: SourceLoader,
    private val ioDispatcher: CoroutineDispatcher,
) : AnalysisSources {

    override suspend fun load(path: Path): SourceText? {
        documents.get(path)?.let { document ->
            val session = document.session
            val snapshot = session.text.snapshot.value
            return SourceText(snapshot, session.parse.value?.takeIf { it.version == snapshot.version }, session.tokensFor(snapshot))
        }
        return try {
            val loaded = withContext(ioDispatcher) { sourceLoader.load(path) }
            SourceText(TextSnapshot(loaded.text, 0), parse = null, tokens = null, fileDiagnostics = loaded.diagnostics)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            logger.debug("Not analyzing unreadable {}: {}", path, e.toString())
            null
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(WorkspaceAnalysisSources::class.java)
    }
}
