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

import io.karpfen.ide.core.document.dsl.DslReader
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
 * Facade over one opened folder. It offers the file tree and its watching, the open documents, the modeling contexts,
 * the project analysis and the problems of all files. It coordinates its collaborators and implements no domain logic
 * itself. The collaborators are the scanner, the file watcher, the [DocumentManager], the [SourceLoader], the
 * descriptor reader, the context resolver, the project analyzer and the problems aggregator.
 *
 * The workspace runs four pipelines. All of them live in a child scope of the factory's scope, and [close] cancels
 * them.
 * - The scan fills [files]. Watch events are debounced by `WorkspaceOptions.watchDebounce`. They lead to a rescan and
 *   to a reload or a conflict for each affected open document.
 * - The tree and the environment descriptors give [contexts]. A descriptor is loaded with the [SourceLoader]. While it
 *   is open in the editor, its editor text is used instead and read again on every edit.
 * - The contexts and the texts of the open documents give [analysis]. Every change schedules a new analysis after the
 *   `analysisDebounce` delay, and a newer request cancels a running one. After each analysis the project diagnostics
 *   of each file go into its open session through `setExternalDiagnostics(ExternalDiagnosticSources.PROJECT,
 *   analyzedFile.snapshot, ...)`. The session ignores a snapshot of another lineage. That happens, for example, when a
 *   document was closed and reopened while the analysis ran.
 * - The analysis, the diagnostics of the open documents and the texts of the read descriptors with their syntax errors
 *   give [problems].
 *
 * Use the workspace and its [DocumentManager] from the dispatcher of the scope given to the factory. In the app that
 * is the UI thread, in tests the test thread. Every change to the `EditableText` of an open document runs on that
 * dispatcher, because an `EditableText` may only be changed from one thread. Such changes are a reload as an
 * `External` change, the reload that resolves a conflict, and `markSavePoint` after a save.
 * `WorkspaceOptions.ioDispatcher` and `WorkspaceOptions.analysisDispatcher` only do file I/O and read immutable
 * snapshots.
 */
public interface Workspace : AutoCloseable {

    /** The opened folder, absolute and normalized. */
    public val root: Path

    /** The language supports used for the documents and for the analysis. */
    public val languages: LanguageRegistry

    /** The current file tree. It is [WorkspaceTree.empty] until the first scan completes. */
    public val files: StateFlow<WorkspaceTree>

    /**
     * Counts the scans of the folder that have completed. It grows whenever the IDE noticed a change on disk or was
     * asked to [refresh], also when the tree of [files] stayed the same, as after a change inside a file. Whoever
     * reads a file that is no open document, such as the layout of a diagram, reads it again when this value changes.
     */
    public val scanRevision: StateFlow<Long>

    /** The current modeling contexts, the loose files and the diagnostics of the context resolution. */
    public val contexts: StateFlow<ContextResolution>

    /** The latest completed project analysis. It is [ProjectAnalysis.EMPTY] before the first one. */
    public val analysis: StateFlow<ProjectAnalysis>

    /**
     * Every diagnostic of every file, ordered and filtered by the rules of [WorkspaceProblems]. It is updated after
     * every analysis and whenever the diagnostics of an open document change. It is [WorkspaceProblems.EMPTY] before
     * the first analysis.
     */
    public val problems: StateFlow<WorkspaceProblems>

    /** True while an analysis is scheduled or running. The status bar shows "Analyzing..." from it. */
    public val isAnalyzing: StateFlow<Boolean>

    /** Opens, saves and closes the documents of this workspace. */
    public val documents: DocumentManager

    /**
     * Rescans the folder now, without waiting for the watch debounce, and suspends until [files] and [contexts] are
     * updated.
     */
    public suspend fun refresh()

    /**
     * Runs an analysis of the current state now and returns it once [analysis] and [problems] reflect it. It does not
     * wait for the debounce and replaces an analysis that is only scheduled. The command line tool and the tests use
     * it.
     */
    public suspend fun awaitAnalysis(): ProjectAnalysis

    /**
     * Stops watching and analysis and closes every open document. Unsaved changes are discarded. Calling it again does
     * nothing.
     */
    override fun close()
}

/**
 * Opens the folder [root] as a workspace and starts scanning. It also starts watching, unless [options] turn that
 * off. Every collaborator can be injected, and the defaults are the production implementations. The factory creates
 * the workspace's own scope as a supervisor child of [scope] and creates the [DocumentManager] in it. It hands every
 * collaborator to the workspace, which never creates one itself.
 *
 * @param root an existing directory.
 * @param scope the parent scope of all workspace work. Its dispatcher is the single writer of the documents' texts,
 *   as described on [Workspace]. Cancelling it has the same effect as [Workspace.close].
 * @param reader the dsl-tools adapter of the default [analyzer].
 * @param fileStore reads files through the default [sourceLoader] and writes them when a document is saved.
 * @throws IllegalArgumentException if [root] is not a directory.
 */
public fun Workspace(
    root: Path,
    scope: CoroutineScope,
    options: WorkspaceOptions = WorkspaceOptions(),
    languages: LanguageRegistry = defaultLanguageRegistry(),
    reader: DslReader = DslReader(),
    fileStore: TextFileStore = TextFileStore(),
    fileWatcher: FileWatcher = FileWatcher(),
    sourceLoader: SourceLoader = SourceLoader(fileStore),
    descriptorReader: EnvironmentDescriptorReader = EnvironmentDescriptorReader(),
    contextResolver: ContextResolver = ContextResolver(),
    analyzer: ProjectAnalyzer = ProjectAnalyzer(languages, reader),
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
 * Opens folders as workspaces. The workbench and the command line tool create their [Workspace]s through this seam.
 * Tests pass a fake, or a factory with test options such as dispatchers, a time source or a file watcher.
 */
public fun interface WorkspaceFactory {
    /**
     * The workspace of [root]. The dispatcher of [scope] becomes the single writer of the documents' texts, as
     * described on [Workspace].
     */
    public fun open(root: Path, scope: CoroutineScope): Workspace
}

/**
 * The default [WorkspaceFactory]. It opens production workspaces with [options], [fileWatcher] and [reader]. The same
 * [reader] serves both the standalone semantics of the default languages and the project analysis. Every other
 * collaborator is the default of the [Workspace] factory. The function only wires things together.
 */
public fun WorkspaceFactory(
    options: WorkspaceOptions = WorkspaceOptions(),
    fileWatcher: FileWatcher = FileWatcher(),
    reader: DslReader = DslReader(),
): WorkspaceFactory {
    val languages = defaultLanguageRegistry(reader)
    return WorkspaceFactory { root, scope -> Workspace(root, scope, options, languages, reader, fileWatcher = fileWatcher) }
}
