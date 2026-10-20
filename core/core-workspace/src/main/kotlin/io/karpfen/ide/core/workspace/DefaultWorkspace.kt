package io.karpfen.ide.core.workspace

import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.analysis.ProjectAnalyzer
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.context.ContextResolver
import io.karpfen.ide.core.workspace.document.DocumentManager
import io.karpfen.ide.core.workspace.document.SourceLoader
import io.karpfen.ide.core.workspace.document.TextFileStore
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptorReader
import io.karpfen.ide.core.workspace.files.FileWatcher
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/** The injected collaborators of a [DefaultWorkspace] (see the `Workspace` factory). */
internal data class WorkspaceCollaborators(
    val fileStore: TextFileStore,
    val fileWatcher: FileWatcher,
    val sourceLoader: SourceLoader,
    val descriptorReader: EnvironmentDescriptorReader,
    val contextResolver: ContextResolver,
    val analyzer: ProjectAnalyzer,
)

/**
 * Default [Workspace]: wires `WorkspaceScanner`, `DefaultDocumentManager` and the injected [collaborators] and runs the
 * pipelines described on [Workspace]. It only coordinates: each pipeline is its own internal class (e.g. a tree
 * refresher for scan + watch debounce + routing changes to `DefaultDocumentManager.onFileChanged`, an analysis
 * scheduler, a diagnostics publisher, and `WorkspaceProblemsAggregator` for [problems]), so no class grows beyond one
 * responsibility (spec §1a).
 */
internal class DefaultWorkspace(
    override val root: Path,
    private val parentScope: CoroutineScope,
    private val options: WorkspaceOptions,
    override val languages: LanguageRegistry,
    private val collaborators: WorkspaceCollaborators,
) : Workspace {

    override val files: StateFlow<WorkspaceTree>
        get() = TODO("WP-WORKSPACE: scanned tree")

    override val contexts: StateFlow<ContextResolution>
        get() = TODO("WP-WORKSPACE: resolved contexts")

    override val analysis: StateFlow<ProjectAnalysis>
        get() = TODO("WP-WORKSPACE: debounced project analysis")

    override val problems: StateFlow<WorkspaceProblems>
        get() = TODO("WP-WORKSPACE: aggregated problems")

    override val isAnalyzing: StateFlow<Boolean>
        get() = TODO("WP-WORKSPACE: analyzing flag")

    override val documents: DocumentManager
        get() = TODO("WP-WORKSPACE: document manager")

    override suspend fun refresh(): Unit = TODO("WP-WORKSPACE: rescan")

    override suspend fun awaitAnalysis(): ProjectAnalysis = TODO("WP-WORKSPACE: immediate analysis")

    override fun close(): Unit = TODO("WP-WORKSPACE: close")
}
