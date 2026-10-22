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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * The contexts of a workspace together with the descriptor texts their ranges refer to.
 *
 * @property descriptorTexts the normalized text of every readable descriptor (the `TextSnapshot` the descriptor
 *   diagnostics' ranges refer to, e.g. for `FileProblems.snapshot`).
 */
internal data class ContextState(val resolution: ContextResolution, val descriptorTexts: Map<Path, TextSnapshot>) {
    companion object {
        /** Before the first scan. */
        val EMPTY: ContextState = ContextState(ContextResolution.EMPTY, emptyMap())
    }
}

/**
 * Keeps the modeling contexts of a workspace up to date: for a scanned tree it loads every descriptor through
 * the workspace's [SourceLoader] (the one loading rule, so ranges refer to the normalized text and loading findings
 * such as malformed UTF-8 are reported on the descriptor), reads it with the [descriptorReader] and resolves the
 * contexts with the [resolver]. Loading and reading run on [ioDispatcher]; a descriptor that cannot be read is an ERROR
 * `unreadable`, like every file the workspace cannot read, and defines no context.
 */
internal class ContextTracker(
    private val sourceLoader: SourceLoader,
    private val descriptorReader: EnvironmentDescriptorReader,
    private val resolver: ContextResolver,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val stateFlow = MutableStateFlow(ContextState.EMPTY)
    private val resolutionFlow = MutableStateFlow(ContextResolution.EMPTY)

    /** Contexts and descriptor texts, replaced together. */
    val state: StateFlow<ContextState> = stateFlow.asStateFlow()

    /** The contexts alone (`Workspace.contexts`); updated right after [state]. */
    val resolution: StateFlow<ContextResolution> = resolutionFlow.asStateFlow()

    /** Resolves the contexts of [tree] and publishes them. */
    suspend fun update(tree: WorkspaceTree) {
        val descriptors = withContext(ioDispatcher) {
            tree.files(FileKind.ENV_DESCRIPTOR).associate { file -> file.path to read(file.path) }
        }
        val resolution = resolver.resolve(tree, descriptors.mapValues { it.value.result })
        stateFlow.value = ContextState(resolution, descriptors.mapNotNull { (path, read) -> read.text?.let { path to it } }.toMap())
        resolutionFlow.value = resolution
    }

    /**
     * Loads and reads the descriptor at [path], its loading findings merged into the reader's; a descriptor that cannot
     * be loaded yields only an `unreadable` finding and no text.
     */
    private fun read(path: Path): ReadDescriptor =
        try {
            val loaded = sourceLoader.load(path)
            val result = descriptorReader.read(path, loaded.text)
            val withLoadingFindings = result.copy(diagnostics = (result.diagnostics + loaded.diagnostics).sortedWith(Diagnostic.ORDER))
            ReadDescriptor(withLoadingFindings, TextSnapshot(loaded.text, 0))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            logger.info("Cannot read environment descriptor {}: {}", path, e.toString())
            ReadDescriptor(DescriptorReadResult(null, listOf(UnreadableFindings.file(ioFailureReason(e))), emptyList()), text = null)
        }

    /** The read [result] of a descriptor and its normalized [text], null when it could not be loaded. */
    private class ReadDescriptor(val result: DescriptorReadResult, val text: TextSnapshot?)

    private companion object {
        private val logger = LoggerFactory.getLogger(ContextTracker::class.java)
    }
}
