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
 * [FileWatcher] on `java.nio.file.WatchService`. It registers directories recursively through a [WatchRegistry]. Every
 * directory below the root that is not ignored is registered when the flow starts. Every directory created later is
 * registered as soon as its creation is reported. Symbolic links are followed the way the scan follows them, which
 * covers a linked root, linked directories and the targets of linked files. Changes are reported under the paths the
 * workspace uses. Blocking waits run on [dispatcher] and are interrupted when the collector is cancelled.
 *
 * Errors of the service end the flow with one [FileChangeKind.OVERFLOW] change, as described on [FileWatcher.watch].
 * Such errors are an I/O error while registering, nothing to watch, the root disappearing, or the service closing.
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

    /**
     * Emits the changes of every signalled key until the key of the root becomes invalid or nothing of the tree is left
     * to watch.
     */
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

    /** Logs why watching [root] stopped and ends the flow with one overflow change of [root]. */
    private suspend fun FlowCollector<FileChange>.endWithOverflow(root: Path, cause: String) {
        logger.warn("Watching {} stopped: {}", root, cause)
        emit(FileChange(root, FileChangeKind.OVERFLOW))
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(WatchServiceFileWatcher::class.java)
    }
}
