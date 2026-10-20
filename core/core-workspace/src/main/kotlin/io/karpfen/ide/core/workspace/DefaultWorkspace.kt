package io.karpfen.ide.core.workspace

import io.karpfen.ide.core.document.dsl.DslConverter
import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.document.DocumentManager
import io.karpfen.ide.core.workspace.document.TextFileStore
import io.karpfen.ide.core.workspace.files.FileWatcher
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * Default [Workspace]: wires `WorkspaceScanner`, [FileWatcher], `DefaultDocumentManager`,
 * `EnvironmentDescriptorReader`, `ContextResolver` and `ProjectAnalyzer` and runs the pipelines described on
 * [Workspace].
 */
internal class DefaultWorkspace(
    override val root: Path,
    private val parentScope: CoroutineScope,
    private val options: WorkspaceOptions,
    override val languages: LanguageRegistry,
    private val converter: DslConverter,
    private val fileStore: TextFileStore,
    private val fileWatcher: FileWatcher,
) : Workspace {

    override val files: StateFlow<WorkspaceTree>
        get() = TODO("WP-WORKSPACE: scanned tree")

    override val contexts: StateFlow<ContextResolution>
        get() = TODO("WP-WORKSPACE: resolved contexts")

    override val analysis: StateFlow<ProjectAnalysis>
        get() = TODO("WP-WORKSPACE: debounced project analysis")

    override val isAnalyzing: StateFlow<Boolean>
        get() = TODO("WP-WORKSPACE: analyzing flag")

    override val documents: DocumentManager
        get() = TODO("WP-WORKSPACE: document manager")

    override suspend fun refresh(): Unit = TODO("WP-WORKSPACE: rescan")

    override suspend fun awaitAnalysis(): ProjectAnalysis = TODO("WP-WORKSPACE: immediate analysis")

    override fun close(): Unit = TODO("WP-WORKSPACE: close")
}
