package io.karpfen.ide.core.workspace.problems

import io.karpfen.ide.core.workspace.analysis.PublishedAnalysis
import io.karpfen.ide.core.workspace.document.OpenDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Publishes `Workspace.problems`: the [aggregator]'s rule applied to the latest analysis (with its descriptors)
 * and the current diagnostics of every open document, recomputed on [dispatcher] whenever one of them changes.
 * [WorkspaceProblems.EMPTY] until the first analysis.
 */
internal class ProblemsPublisher(
    private val aggregator: WorkspaceProblemsAggregator,
    private val dispatcher: CoroutineDispatcher,
) {

    private val state = MutableStateFlow(WorkspaceProblems.EMPTY)

    /** The latest problems. */
    val problems: StateFlow<WorkspaceProblems> = state.asStateFlow()

    /** Starts following [analyses] and [documents] in [scope]. */
    fun start(scope: CoroutineScope, analyses: StateFlow<PublishedAnalysis>, documents: StateFlow<List<OpenDocument>>): Job =
        scope.launch {
            combine(analyses, openDiagnostics(documents)) { published, open ->
                if (published.analysis.generation == 0L) {
                    WorkspaceProblems.EMPTY
                } else {
                    aggregator.aggregate(published.analysis, open, published.descriptors)
                }
            }.flowOn(dispatcher).collect { state.value = it }
        }

    /**
     * The current diagnostics of every open document, re-emitted whenever the set or one document's diagnostics change.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun openDiagnostics(documents: StateFlow<List<OpenDocument>>): Flow<List<OpenDocumentDiagnostics>> =
        documents.flatMapLatest { open ->
            if (open.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(open.map { document ->
                    document.session.diagnostics.map { OpenDocumentDiagnostics(document.path, document.fileKind, it) }
                }) { it.toList() }
            }
        }
}
