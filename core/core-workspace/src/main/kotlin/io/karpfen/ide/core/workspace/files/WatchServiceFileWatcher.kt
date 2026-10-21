package io.karpfen.ide.core.workspace.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchService

/**
 * [FileWatcher] on `java.nio.file.WatchService`, registering directories recursively through a [WatchRegistry]: every
 * directory below the root that is not ignored is registered when the flow starts, and every directory created later
 * as soon as its creation is reported. Symbolic links are followed like the scan follows them (a linked root, linked
 * directories, the targets of linked files), and changes are reported under the paths the workspace uses. Blocking
 * waits run on [dispatcher] and are interrupted when the collector is cancelled.
 *
 * Errors of the service (an I/O error while registering, nothing to watch, the root disappearing, the service closing)
 * end the flow with one [FileChangeKind.OVERFLOW] change, as [FileWatcher.watch] documents.
 */
internal class WatchServiceFileWatcher(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FileWatcher {

    override fun watch(root: Path, isIgnored: (Path) -> Boolean): Flow<FileChange> {
        val normalizedRoot = root.toAbsolutePath().normalize()
        return flow {
            try {
                normalizedRoot.fileSystem.newWatchService().use { service ->
                    val registry = WatchRegistry(isIgnored) { it.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY) }
                    registry.registerTree(normalizedRoot)
                    if (registry.watchesTree) {
                        pump(service, registry, normalizedRoot)
                    } else {
                        endWithOverflow(normalizedRoot, "nothing could be registered")
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: IOException) {
                endWithOverflow(normalizedRoot, e.toString())
            } catch (e: ClosedWatchServiceException) {
                endWithOverflow(normalizedRoot, e.toString())
            }
        }.flowOn(dispatcher)
    }

    /** Emits the changes of every signalled key until the root's key becomes invalid or nothing of the tree is left. */
    private suspend fun FlowCollector<FileChange>.pump(service: WatchService, registry: WatchRegistry, root: Path) {
        while (true) {
            val key = runInterruptible { service.take() }
            for (change in registry.changesOf(key, key.pollEvents())) emit(change)
            if (!key.reset() && (registry.cancel(key) == root || !registry.watchesTree)) {
                emit(FileChange(root, FileChangeKind.OVERFLOW))
                return
            }
        }
    }

    private suspend fun FlowCollector<FileChange>.endWithOverflow(root: Path, cause: String) {
        logger.warn("Watching {} stopped: {}", root, cause)
        emit(FileChange(root, FileChangeKind.OVERFLOW))
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(WatchServiceFileWatcher::class.java)
    }
}
