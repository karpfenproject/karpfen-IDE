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
package io.karpfen.ide.core.workspace.context

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.diagnostics.UnreadableFindings
import io.karpfen.ide.core.workspace.document.SourceLoader
import io.karpfen.ide.core.workspace.document.ioFailureReason
import io.karpfen.ide.core.workspace.environment.DescriptorReadResult
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptorReader
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * The contexts of a workspace together with the descriptor texts their findings refer to.
 *
 * @property descriptors every readable descriptor's text with the diagnostics of the text itself, by path; the
 *   descriptor's findings in [resolution] refer to that text (e.g. for `FileProblems.snapshot`).
 */
internal data class ContextState(val resolution: ContextResolution, val descriptors: Map<Path, DescriptorSource>) {
    companion object {
        /** Before the first scan. */
        val EMPTY: ContextState = ContextState(ContextResolution.EMPTY, emptyMap())
    }
}

/**
 * Keeps the modeling contexts of a workspace up to date: it reads every descriptor of the scanned tree with the
 * [descriptorReader] and resolves the contexts with the [resolver].
 *
 * A descriptor open in an editor is read from its editor text ([openDescriptors]), so unsaved edits count: its findings,
 * the contexts and the analysis follow every edit, and closing it without saving falls back to the disk. Every other
 * descriptor is loaded through the workspace's [SourceLoader] (the one loading rule, so ranges refer to the normalized
 * text and loading findings such as malformed UTF-8 are reported on the descriptor); a descriptor that cannot be read
 * is an ERROR `unreadable`, like every file the workspace cannot read, and defines no context.
 *
 * The descriptor's findings (schema and named files) go into the resolution and become its project diagnostics; the
 * syntax errors and loading findings belong to its text ([DescriptorSource]), as a karpfen file's own diagnostics do.
 *
 * [update] reads everything after a scan. Once [start]ed, the tracker follows the open descriptors itself: whenever one
 * is opened, edited or closed, it reads the open ones again and reuses what the last scan loaded from disk, so following
 * the edits costs no file I/O (a descriptor closed since is loaded again). Updates and the readings that follow the
 * edits are serialized; loading and reading run on [ioDispatcher].
 */
internal class ContextTracker(
    private val sourceLoader: SourceLoader,
    private val descriptorReader: EnvironmentDescriptorReader,
    private val resolver: ContextResolver,
    private val ioDispatcher: CoroutineDispatcher,
    private val openDescriptors: OpenDescriptors,
) {

    private val stateFlow = MutableStateFlow(ContextState.EMPTY)
    private val resolutionFlow = MutableStateFlow(ContextResolution.EMPTY)

    /** Serializes [update] and the readings that follow the edits; guards [tree], [editorTexts] and [diskReads]. */
    private val reading = Mutex()

    /** The last scanned tree, or null before the first scan. */
    private var tree: WorkspaceTree? = null

    /** The editor texts of the open descriptors by path, as [openDescriptors] last emitted them. */
    private var editorTexts: Map<Path, TextSnapshot> = emptyMap()

    /** The descriptors read from disk since the last scan, by path; open descriptors are never kept here. */
    private val diskReads = HashMap<Path, ReadDescriptor>()

    /** Contexts and descriptor texts, replaced together. */
    val state: StateFlow<ContextState> = stateFlow.asStateFlow()

    /** The contexts alone (`Workspace.contexts`); updated right after [state]. */
    val resolution: StateFlow<ContextResolution> = resolutionFlow.asStateFlow()

    /** Reads every descriptor of the scanned [tree] (the disk may have changed) and publishes the contexts. */
    suspend fun update(tree: WorkspaceTree) {
        reading.withLock {
            this.tree = tree
            diskReads.clear()
            resolve(tree)
        }
    }

    /**
     * Follows the open descriptors in [scope] until it ends: every emission of [openDescriptors] becomes the editor texts
     * the descriptors are read from, and the contexts are resolved again (before the first scan there is nothing to
     * resolve). A newer emission supersedes a reading still running.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        openDescriptors.texts().collectLatest { texts ->
            reading.withLock {
                editorTexts = texts
                tree?.let { resolve(it) }
            }
        }
    }

    /** Reads the descriptors of [tree] and publishes the contexts they define. */
    private suspend fun resolve(tree: WorkspaceTree) {
        val descriptors = withContext(ioDispatcher) {
            tree.files(FileKind.ENV_DESCRIPTOR).associate { file -> file.path to read(file.path) }
        }
        val resolution = resolver.resolve(tree, descriptors.mapValues { it.value.result })
        stateFlow.value = ContextState(resolution, descriptors.mapNotNull { (path, read) -> read.source?.let { path to it } }.toMap())
        resolutionFlow.value = resolution
    }

    /** The descriptor at [path] from its editor text if it is open, else from disk (once per scan). */
    private fun read(path: Path): ReadDescriptor {
        val edited = editorTexts[path]
        if (edited != null) {
            diskReads.remove(path)
            val result = descriptorReader.read(path, edited.text)
            return ReadDescriptor(result, DescriptorSource(edited, result.syntaxDiagnostics))
        }
        return diskReads.getOrPut(path) { readFromDisk(path) }
    }

    /**
     * Loads and reads the descriptor at [path], its loading findings joining its syntax errors as the text's own; a
     * descriptor that cannot be loaded yields only an `unreadable` finding and no text.
     */
    private fun readFromDisk(path: Path): ReadDescriptor =
        try {
            val loaded = sourceLoader.load(path)
            val result = descriptorReader.read(path, loaded.text)
            val own = (result.syntaxDiagnostics + loaded.diagnostics).sortedWith(Diagnostic.ORDER)
            ReadDescriptor(result, DescriptorSource(TextSnapshot(loaded.text, 0), own))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            logger.info("Cannot read environment descriptor {}: {}", path, e.toString())
            ReadDescriptor(DescriptorReadResult(null, listOf(UnreadableFindings.file(ioFailureReason(e))), emptyList()), source = null)
        }

    /** The read [result] of a descriptor and the [source] it was read from, null when it could not be loaded. */
    private class ReadDescriptor(val result: DescriptorReadResult, val source: DescriptorSource?)

    private companion object {
        private val logger = LoggerFactory.getLogger(ContextTracker::class.java)
    }
}
