package io.karpfen.ide.core.workspace

import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.workspace.analysis.AnalysisScheduler
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.analysis.ProjectAnalyzer
import io.karpfen.ide.core.workspace.analysis.ProjectDiagnosticsPublisher
import io.karpfen.ide.core.workspace.analysis.WorkspaceAnalysisSources
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.context.ContextResolver
import io.karpfen.ide.core.workspace.context.ContextTracker
import io.karpfen.ide.core.workspace.document.DocumentManager
import io.karpfen.ide.core.workspace.document.SourceLoader
import io.karpfen.ide.core.workspace.document.TextFileStore
import io.karpfen.ide.core.workspace.document.WorkspaceDocuments
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptorReader
import io.karpfen.ide.core.workspace.files.FileWatcher
import io.karpfen.ide.core.workspace.files.WorkspaceScanner
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import io.karpfen.ide.core.workspace.problems.ProblemsPublisher
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import io.karpfen.ide.core.workspace.problems.WorkspaceProblemsAggregator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The injected I/O and domain collaborators of a [DefaultWorkspace], all created by the `Workspace` factory (never
 * inside the workspace), so tests of the coordinator can replace each of them, the document side ([documents]) and the
 * [scanner] included. A plain holder of services (no value semantics).
 */
internal class WorkspaceCollaborators(
    val fileStore: TextFileStore,
    val fileWatcher: FileWatcher,
    val sourceLoader: SourceLoader,
    val descriptorReader: EnvironmentDescriptorReader,
    val contextResolver: ContextResolver,
    val analyzer: ProjectAnalyzer,
    val documents: WorkspaceDocuments,
    val scanner: WorkspaceScanner,
)

/**
 * Default [Workspace]: runs the pipelines described on [Workspace] over the injected [collaborators]. It only
 * coordinates; each pipeline is its own internal class, built here from the [collaborators], the [scope] and the
 * [options] (they are the workspace's structure, not seams):
 * - [TreeRefresher] — scan, watch debounce, routing changes to `WorkspaceDocuments.onFileChanged` → [files];
 * - [ContextTracker] — descriptors read through the `SourceLoader`, contexts resolved → [contexts];
 * - [AnalysisScheduler] over [WorkspaceAnalysisSources] — debounced, cancellable analysis → [analysis], [isAnalyzing];
 * - [ProjectDiagnosticsPublisher] — each `AnalyzedFile`'s project diagnostics into the open sessions;
 * - [ProblemsPublisher] with the [WorkspaceProblemsAggregator] → [problems].
 *
 * Start-up (in [scope]): watching (if enabled), the publishers and the analysis triggers (contexts, completed rescans,
 * every text version of every open document) start, then the first scan runs, then the analysis scheduler serves the
 * request made at construction (so [isAnalyzing] is true from the start) and every trigger after it.
 *
 * @param scope the workspace's own scope (a supervisor child of the factory's scope, created by the factory and shared
 *   with [WorkspaceCollaborators.documents]); [close] cancels it.
 */
internal class DefaultWorkspace(
    override val root: Path,
    private val scope: CoroutineScope,
    private val options: WorkspaceOptions,
    override val languages: LanguageRegistry,
    private val collaborators: WorkspaceCollaborators,
) : Workspace {

    private val contextTracker = ContextTracker(
        collaborators.sourceLoader, collaborators.descriptorReader, collaborators.contextResolver, options.ioDispatcher,
    )
    private val treeRefresher = TreeRefresher(
        root, collaborators.scanner, collaborators.fileWatcher, collaborators.documents, contextTracker,
        options.ioDispatcher, options.watchDebounce,
    )
    private val scheduler = AnalysisScheduler(
        collaborators.analyzer,
        WorkspaceAnalysisSources(collaborators.documents, collaborators.sourceLoader, options.ioDispatcher),
        contextTracker.state,
        options.analysisDispatcher,
        options.analysisDebounce,
    )
    private val diagnosticsPublisher = ProjectDiagnosticsPublisher()
    private val problemsPublisher = ProblemsPublisher(WorkspaceProblemsAggregator(), options.analysisDispatcher)
    private val closed = AtomicBoolean(false)
    private val started: Job

    init {
        scheduler.schedule()
        started = scope.launch { start() }
    }

    override val files: StateFlow<WorkspaceTree> get() = treeRefresher.files

    override val contexts: StateFlow<ContextResolution> get() = contextTracker.resolution

    override val analysis: StateFlow<ProjectAnalysis> get() = scheduler.analysis

    override val problems: StateFlow<WorkspaceProblems> get() = problemsPublisher.problems

    override val isAnalyzing: StateFlow<Boolean> get() = scheduler.isAnalyzing

    override val documents: DocumentManager get() = collaborators.documents

    /** Rescans now and, as the explicit alternative to watching, checks every open document against its file. */
    override suspend fun refresh() {
        treeRefresher.refresh()
    }

    /**
     * Waits for the start-up, runs an analysis now and waits until [problems] reflect it. The wait runs in the
     * workspace's scope, so closing the workspace ends it with a cancellation instead of leaving the caller suspended
     * forever.
     *
     * @throws IllegalStateException if the workspace is closed.
     */
    override suspend fun awaitAnalysis(): ProjectAnalysis {
        check(!closed.get()) { "the workspace of $root is closed" }
        return scope.async {
            started.join()
            val analysis = scheduler.runNow()
            problemsPublisher.problems.first { it.generation >= analysis.generation }
            analysis
        }.await()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scheduler.close()
        collaborators.documents.closeAll()
        scope.cancel(CancellationException("the workspace of $root was closed"))
    }

    /** Starts the pipelines in the order described on the class; a failing first scan is logged, not thrown. */
    private suspend fun start() {
        val documents = collaborators.documents.openDocuments
        if (options.watch) treeRefresher.watch(scope)
        problemsPublisher.start(scope, scheduler.published, documents)
        diagnosticsPublisher.start(scope, scheduler.analysis, documents)
        launchAnalysisTriggers()
        try {
            treeRefresher.refresh()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            logger.error("The first scan of {} failed", root, e)
        }
        scheduler.start(scope)
    }

    /**
     * Schedules an analysis on every change of the contexts, every completed rescan and every text version of an open
     * document. It subscribes before the first scan and skips the current state, which the request made at
     * construction already covers.
     */
    private fun launchAnalysisTriggers(): Job = scope.launch {
        combine(contextTracker.state, treeRefresher.revision, openTextVersions()) { _, _, _ -> }
            .drop(1)
            .collect { scheduler.schedule() }
    }

    /** Emits whenever the set of open documents or the text of one of them changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun openTextVersions(): Flow<Any> =
        collaborators.documents.openDocuments.flatMapLatest { open ->
            if (open.isEmpty()) flowOf(open) else combine(open.map { it.session.text.snapshot }) { it.toList() }
        }

    private companion object {
        private val logger = LoggerFactory.getLogger(DefaultWorkspace::class.java)
    }
}
