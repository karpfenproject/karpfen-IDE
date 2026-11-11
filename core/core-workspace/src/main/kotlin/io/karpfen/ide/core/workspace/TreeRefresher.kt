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
package io.karpfen.ide.core.workspace

import io.karpfen.ide.core.workspace.context.ContextTracker
import io.karpfen.ide.core.workspace.document.WorkspaceDocuments
import io.karpfen.ide.core.workspace.files.FileChange
import io.karpfen.ide.core.workspace.files.FileChangeKind
import io.karpfen.ide.core.workspace.files.FileWatcher
import io.karpfen.ide.core.workspace.files.WorkspaceScanner
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import io.karpfen.ide.core.workspace.files.batchedBy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.time.Duration

/**
 * The file side of a workspace. It scans the folder into [files] and keeps the contexts in step through the [contexts]
 * tracker. It routes changes on disk to the open documents through [WorkspaceDocuments.onFileChanged], which decides
 * between a reload, a conflict and nothing.
 *
 * There are two ways in.
 * - [refresh] rescans now and checks every open document against its file. It is the explicit alternative to
 *   watching.
 * - [watch] collects the raw changes of the [watcher] and debounces them into batches by [watchDebounce], which is
 *   200 ms by default. For each batch it rescans and checks the open documents at or below a changed path. After an
 *   overflow it checks all of them.
 *
 * Checking a document means handing it to the document side. That side reads the file and compares it with what the
 * document last saw on disk. So a check without a real change, such as a touch or the document's own save, changes
 * nothing.
 *
 * Rescans run one at a time. [revision] counts the completed ones. The analysis listens to it, so it also follows
 * content changes of files whose entry in the tree stays the same.
 */
internal class TreeRefresher(
    private val root: Path,
    private val scanner: WorkspaceScanner,
    private val watcher: FileWatcher,
    private val documents: WorkspaceDocuments,
    private val contexts: ContextTracker,
    private val ioDispatcher: CoroutineDispatcher,
    private val watchDebounce: Duration,
) {

    private val tree = MutableStateFlow(WorkspaceTree.empty(root))
    private val completedScans = MutableStateFlow(0L)
    /** Serializes the rescans. */
    private val scanning = Mutex()

    /** The latest scanned tree. */
    val files: StateFlow<WorkspaceTree> = tree.asStateFlow()

    /** The number of completed rescans. */
    val revision: StateFlow<Long> = completedScans.asStateFlow()

    /** Rescans the folder and checks every open document against its file. */
    suspend fun refresh() {
        rescan()
        documents.openDocuments.value.forEach { documents.onFileChanged(it.path, deleted = false) }
    }

    /**
     * Starts watching in [scope]. The returned job ends with the scope or when the watcher gives up. After that,
     * changes on disk only show up through an explicit [refresh].
     */
    fun watch(scope: CoroutineScope): Job = scope.launch {
        watcher.watch(root) { scanner.isIgnored(root, it) }
            .batchedBy(watchDebounce)
            .collect { batch -> applyBatch(batch) }
        logger.warn("Watching {} ended; changes on disk are picked up by an explicit refresh only", root)
    }

    /** Rescans and checks the open documents a [batch] concerns. A failure is logged, and the watching goes on. */
    private suspend fun applyBatch(batch: List<FileChange>) {
        try {
            rescan()
            val overflow = batch.any { it.kind == FileChangeKind.OVERFLOW }
            for (document in documents.openDocuments.value.map { it.path }) {
                if (overflow || batch.any { change -> document.startsWith(change.path) }) {
                    documents.onFileChanged(document, deleted = isDeletedIn(batch, document))
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            logger.error("Applying {} file changes below {} failed", batch.size, root, e)
        }
    }

    /**
     * Whether the last change of [document] itself in [batch] is its deletion. Only such a deletion is known for sure.
     * In every other case the document side reads the file, so a deleted directory or a lost event shows up there as a
     * missing file.
     */
    private fun isDeletedIn(batch: List<FileChange>, document: Path): Boolean =
        batch.lastOrNull { it.path == document }?.kind == FileChangeKind.DELETED

    /** Scans the folder, publishes the tree, updates the contexts and counts the scan. Only one scan runs at a time. */
    private suspend fun rescan() {
        scanning.withLock {
            val scanned = withContext(ioDispatcher) { scanner.scan(root) }
            tree.value = scanned
            contexts.update(scanned)
            completedScans.update { it + 1 }
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(TreeRefresher::class.java)
    }
}
