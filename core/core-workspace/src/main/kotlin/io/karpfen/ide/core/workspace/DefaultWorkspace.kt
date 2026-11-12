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

import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.workspace.analysis.AnalysisScheduler
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.analysis.ProjectAnalyzer
import io.karpfen.ide.core.workspace.analysis.ProjectDiagnosticsPublisher
import io.karpfen.ide.core.workspace.analysis.WorkspaceAnalysisSources
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.context.ContextResolver
import io.karpfen.ide.core.workspace.context.ContextTracker
import io.karpfen.ide.core.workspace.context.OpenDescriptors
import io.karpfen.ide.core.workspace.document.DocumentManager
import io.karpfen.ide.core.workspace.document.SourceLoader
import io.karpfen.ide.core.workspace.document.TextFileStore
import io.karpfen.ide.core.workspace.document.WorkspaceDocuments
import io.karpfen.ide.core.workspace.document.openTexts
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptorReader
import io.karpfen.ide.core.workspace.files.FileWatcher
import io.karpfen.ide.core.workspace.files.WorkspaceScanner
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import io.karpfen.ide.core.workspace.problems.ProblemsPublisher
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import io.karpfen.ide.core.workspace.problems.WorkspaceProblemsAggregator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The injected I/O and domain collaborators of a [DefaultWorkspace]. The `Workspace` factory creates all of them, and
 * the workspace never creates one itself. So tests of the coordinator can replace each of them, including the
 * document side in [documents] and the [scanner]. This is a plain holder of services without value semantics.
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
 * Default [Workspace]. It runs the pipelines described on [Workspace] over the injected [collaborators] and only
 * coordinates. Each pipeline is its own internal class, built here from the [collaborators], the [scope] and the
 * [options]. These classes are the structure of the workspace, not seams for tests.
 * - [TreeRefresher] scans, debounces watch events, routes changes to `WorkspaceDocuments.onFileChanged` and fills
 *   [files].
 * - [ContextTracker] reads the descriptors through the `SourceLoader`, or takes the editor text while a descriptor is
 *   open, resolves the contexts and fills [contexts]. It follows the open descriptors itself through
 *   [OpenDescriptors].
 * - [AnalysisScheduler] runs the debounced, cancellable analysis over [WorkspaceAnalysisSources] and fills [analysis]
 *   and [isAnalyzing].
 * - [ProjectDiagnosticsPublisher] puts the project diagnostics of each `AnalyzedFile` into the open sessions.
 * - [ProblemsPublisher] fills [problems] with the help of the [WorkspaceProblemsAggregator].
 *
 * The start-up runs in [scope] in this order. First the watching starts if it is enabled. Then the publishers, the
 * context tracker and the analysis triggers start. The triggers are the contexts, the completed rescans and every text
 * version of every open document. Then the first scan runs. Last, the analysis scheduler starts and serves the request
 * made at construction and every trigger after it. Because of that early request, [isAnalyzing] is true from the
 * start.
 *
 * @param scope the workspace's own scope. The factory creates it as a supervisor child of its own scope and shares it
 *   with [WorkspaceCollaborators.documents]. [close] cancels it.
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
        OpenDescriptors(collaborators.documents),
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
    /** Set once by [close]. */
    private val closed = AtomicBoolean(false)

    /** The start-up described on the class. [awaitAnalysis] waits for it. */
    private val started: Job

    init {
        scheduler.schedule()
        started = scope.launch { start() }
    }

    override val files: StateFlow<WorkspaceTree> get() = treeRefresher.files
    override val scanRevision: StateFlow<Long> get() = treeRefresher.revision

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

    /** Starts the pipelines in the order described on the class. A failing first scan is logged, not thrown. */
    private suspend fun start() {
        val documents = collaborators.documents.openDocuments
        if (options.watch) treeRefresher.watch(scope)
        problemsPublisher.start(scope, scheduler.published, documents)
        diagnosticsPublisher.start(scope, scheduler.published, documents)
        contextTracker.start(scope)
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
        combine(contextTracker.state, treeRefresher.revision, collaborators.documents.openTexts()) { _, _, _ -> }
            .drop(1)
            .collect { scheduler.schedule() }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(DefaultWorkspace::class.java)
    }
}
