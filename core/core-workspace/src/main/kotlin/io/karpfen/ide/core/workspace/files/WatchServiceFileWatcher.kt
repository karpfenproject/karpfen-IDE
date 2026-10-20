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
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes

/**
 * [FileWatcher] on `java.nio.file.WatchService`, registering directories recursively: every directory below the root
 * that is not ignored is registered when the flow starts, and every directory created later as soon as its creation is
 * reported. Blocking waits run on [dispatcher] and are interrupted when the collector is cancelled.
 *
 * Errors of the service (an I/O error while registering, the root disappearing, the service closing) end the flow with
 * one [FileChangeKind.OVERFLOW] change, as [FileWatcher.watch] documents.
 */
internal class WatchServiceFileWatcher(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FileWatcher {

    override fun watch(root: Path, isIgnored: (Path) -> Boolean): Flow<FileChange> {
        val normalizedRoot = root.toAbsolutePath().normalize()
        return flow {
            try {
                normalizedRoot.fileSystem.newWatchService().use { service ->
                    val registration = RecursiveRegistration(service, isIgnored)
                    registration.registerTree(normalizedRoot)
                    registration.pump(normalizedRoot, this)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: IOException) {
                endWithOverflow(normalizedRoot, e)
            } catch (e: ClosedWatchServiceException) {
                endWithOverflow(normalizedRoot, e)
            }
        }.flowOn(dispatcher)
    }

    private suspend fun FlowCollector<FileChange>.endWithOverflow(root: Path, cause: Exception) {
        logger.warn("Watching {} stopped: {}", root, cause.toString())
        emit(FileChange(root, FileChangeKind.OVERFLOW))
    }

    /** The registered directories of one [service] (confined to the flow's coroutine). */
    private class RecursiveRegistration(private val service: WatchService, private val isIgnored: (Path) -> Boolean) {

        private val directories = HashMap<WatchKey, Path>()

        /** Registers [directory] and every non-ignored directory below it (ignored subtrees are not entered). */
        fun registerTree(directory: Path) {
            Files.walkFileTree(
                directory,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (dir != directory && isIgnored(dir)) return FileVisitResult.SKIP_SUBTREE
                        register(dir)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
        }

        /** Emits the changes of every signalled key to [collector] until the root's key becomes invalid. */
        suspend fun pump(root: Path, collector: FlowCollector<FileChange>) {
            while (true) {
                val key = runInterruptible { service.take() }
                val directory = directories[key]
                if (directory != null) {
                    for (event in key.pollEvents()) emitEvent(directory, event, collector)
                }
                if (!key.reset()) {
                    directories.remove(key)
                    if (directory == root || directories.isEmpty()) {
                        collector.emit(FileChange(root, FileChangeKind.OVERFLOW))
                        return
                    }
                }
            }
        }

        private suspend fun emitEvent(directory: Path, event: WatchEvent<*>, collector: FlowCollector<FileChange>) {
            if (event.kind() == OVERFLOW) {
                collector.emit(FileChange(directory, FileChangeKind.OVERFLOW))
                return
            }
            val path = directory.resolve(event.context() as Path).normalize()
            if (isIgnored(path)) return
            val kind = when (event.kind()) {
                ENTRY_CREATE -> FileChangeKind.CREATED
                ENTRY_DELETE -> FileChangeKind.DELETED
                else -> FileChangeKind.MODIFIED
            }
            if (kind == FileChangeKind.CREATED && Files.isDirectory(path)) {
                try {
                    registerTree(path)
                } catch (e: IOException) {
                    // The directory vanished again; its deletion is reported by its parent.
                    logger.debug("Cannot register new directory {}: {}", path, e.toString())
                }
            }
            collector.emit(FileChange(path, kind))
        }

        private fun register(directory: Path) {
            directories[directory.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)] = directory
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(WatchServiceFileWatcher::class.java)
    }
}
