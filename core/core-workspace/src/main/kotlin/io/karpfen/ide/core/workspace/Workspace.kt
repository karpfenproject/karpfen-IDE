package io.karpfen.ide.core.workspace

import io.karpfen.ide.core.document.dsl.DslConverter
import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.analysis.ProjectAnalyzer
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.context.ContextResolver
import io.karpfen.ide.core.workspace.document.DefaultDocumentManager
import io.karpfen.ide.core.workspace.document.DocumentManager
import io.karpfen.ide.core.workspace.document.SourceLoader
import io.karpfen.ide.core.workspace.document.TextFileStore
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptorReader
import io.karpfen.ide.core.workspace.files.FileWatcher
import io.karpfen.ide.core.workspace.files.WorkspaceScanner
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Files
import java.nio.file.Path

/**
 * Facade over one opened folder: file tree and watching, documents, modeling contexts, project analysis and problems.
 * It coordinates its collaborators (scanner, watcher, [DocumentManager], [SourceLoader], descriptor reader, context
 * resolver, project analyzer, problems aggregator) and implements no domain logic itself.
 *
 * Pipelines (all in a child scope of the factory's scope, cancelled by [close]):
 * - scan → [files]; watch events are debounced (`WorkspaceOptions.watchDebounce`) and trigger a rescan plus reload or
 *   conflict handling of open documents;
 * - tree + descriptors (loaded with the [SourceLoader], or the editor text of an open descriptor, which is read again on
 *   every edit) → [contexts];
 * - contexts + text of open documents (on every change) → debounced (`analysisDebounce`), cancellable re-analysis →
 *   [analysis]; afterwards the per-file project diagnostics are pushed into the open sessions with
 *   `setExternalDiagnostics(ExternalDiagnosticSources.PROJECT, analyzedFile.snapshot, …)` (the session ignores a
 *   snapshot of another lineage, e.g. of a document closed and reopened during the analysis);
 * - analysis + the open documents' diagnostics + the read descriptors' texts with their syntax errors → [problems].
 *
 * Threading (MUST): the workspace and its [DocumentManager] are used from the dispatcher of the scope given to the
 * factory (the UI thread in the app, the test thread in tests). Every mutation of an open document's `EditableText`
 * (reload as an `External` change, conflict RELOAD, `markSavePoint` after a save) runs on that dispatcher, because an
 * `EditableText` is mutated from one thread only; `WorkspaceOptions.ioDispatcher` and `analysisDispatcher` only do
 * file I/O and read the immutable snapshots.
 */
public interface Workspace : AutoCloseable {

    /** The opened folder (absolute, normalized). */
    public val root: Path

    /** Language supports used for documents and analysis. */
    public val languages: LanguageRegistry

    /** The current file tree; [WorkspaceTree.empty] until the first scan completes. */
    public val files: StateFlow<WorkspaceTree>

    /** The current modeling contexts, loose files and resolution diagnostics. */
    public val contexts: StateFlow<ContextResolution>

    /** The latest completed project analysis ([ProjectAnalysis.EMPTY] before the first one). */
    public val analysis: StateFlow<ProjectAnalysis>

    /**
     * Every diagnostic of every file under one rule (see [WorkspaceProblems]); updated after every analysis and
     * whenever the diagnostics of an open document change. [WorkspaceProblems.EMPTY] before the first analysis.
     */
    public val problems: StateFlow<WorkspaceProblems>

    /** True while an analysis is scheduled or running (status bar "Analyzing…"). */
    public val isAnalyzing: StateFlow<Boolean>

    /** Opening, saving and closing documents of this workspace. */
    public val documents: DocumentManager

    /** Rescans the folder now (bypassing the watch debounce) and suspends until [files] and [contexts] are updated. */
    public suspend fun refresh()

    /**
     * Runs an analysis of the current state now (bypassing the debounce, superseding a scheduled one) and returns it
     * once [analysis] and [problems] reflect it. Used by the CLI and by tests.
     */
    public suspend fun awaitAnalysis(): ProjectAnalysis

    /** Stops watching and analysis and closes every open document (unsaved changes are discarded). Idempotent. */
    override fun close()
}

/**
 * Opens the folder [root] as a workspace and starts scanning (and watching, unless disabled in [options]). Every
 * collaborator is injectable; the defaults are the production implementations. The factory creates the workspace's own
 * scope (a supervisor child of [scope]) and the [DocumentManager] in it, and hands every collaborator to the workspace,
 * which never creates one itself.
 *
 * @param root an existing directory.
 * @param scope parent scope of all workspace work; its dispatcher is the single writer of the documents' texts (see
 *   [Workspace], Threading). Cancelling it has the effect of [Workspace.close].
 * @param converter the dsl-tools adapter of the default [analyzer].
 * @param fileStore reads (through the default [sourceLoader]) and writes (saving) files.
 * @throws IllegalArgumentException if [root] is not a directory.
 */
public fun Workspace(
    root: Path,
    scope: CoroutineScope,
    options: WorkspaceOptions = WorkspaceOptions(),
    languages: LanguageRegistry = defaultLanguageRegistry(),
    converter: DslConverter = DslConverter(),
    fileStore: TextFileStore = TextFileStore(),
    fileWatcher: FileWatcher = FileWatcher(),
    sourceLoader: SourceLoader = SourceLoader(fileStore),
    descriptorReader: EnvironmentDescriptorReader = EnvironmentDescriptorReader(),
    contextResolver: ContextResolver = ContextResolver(),
    analyzer: ProjectAnalyzer = ProjectAnalyzer(languages, converter),
): Workspace {
    val normalizedRoot = root.toAbsolutePath().normalize()
    require(Files.isDirectory(normalizedRoot)) { "workspace root $normalizedRoot is not a directory" }
    val workspaceScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    val documents = DefaultDocumentManager(
        workspaceScope, languages, sourceLoader, fileStore, options.ioDispatcher, options.sessionConfig, options.timeSource,
    )
    return DefaultWorkspace(
        root = normalizedRoot,
        scope = workspaceScope,
        options = options,
        languages = languages,
        collaborators = WorkspaceCollaborators(
            fileStore, fileWatcher, sourceLoader, descriptorReader, contextResolver, analyzer,
            documents = documents,
            scanner = WorkspaceScanner(options.ignoredDirectoryNames),
        ),
    )
}

/**
 * Opens folders as workspaces: the seam through which the workbench and the CLI create their [Workspace]s, so tests
 * pass a fake or a factory with test options (dispatchers, time source, file watcher).
 */
public fun interface WorkspaceFactory {
    /** The workspace of [root]; [scope]'s dispatcher is the documents' single writer (see [Workspace], Threading). */
    public fun open(root: Path, scope: CoroutineScope): Workspace
}

/**
 * The default [WorkspaceFactory]: production workspaces with [options], [fileWatcher] and [languages] (every other
 * collaborator as in the [Workspace] factory). Wiring only.
 */
public fun WorkspaceFactory(
    options: WorkspaceOptions = WorkspaceOptions(),
    fileWatcher: FileWatcher = FileWatcher(),
    languages: LanguageRegistry = defaultLanguageRegistry(),
): WorkspaceFactory = WorkspaceFactory { root, scope ->
    Workspace(root, scope, options, languages, fileWatcher = fileWatcher)
}
