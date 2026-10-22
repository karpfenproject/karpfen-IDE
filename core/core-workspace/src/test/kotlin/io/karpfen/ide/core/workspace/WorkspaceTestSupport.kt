package io.karpfen.ide.core.workspace

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.dsl.DslConverter
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.workspace.analysis.ProjectAnalyzer
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.core.workspace.document.OpenResult
import io.karpfen.ide.core.workspace.files.FileChange
import io.karpfen.ide.core.workspace.files.FileChangeKind
import io.karpfen.ide.core.workspace.files.FileWatcher
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.nio.file.Path
import kotlin.time.TestTimeSource

/** A [FileWatcher] that emits the changes a test hands it (the real watch service polls slowly on macOS). */
internal class FakeFileWatcher : FileWatcher {
    private val changes = MutableSharedFlow<FileChange>(extraBufferCapacity = 1024)

    /** The root of the latest [watch] call, null before the first one. */
    var watchedRoot: Path? = null
        private set

    override fun watch(root: Path, isIgnored: (Path) -> Boolean): Flow<FileChange> {
        watchedRoot = root
        return changes.filterNot { isIgnored(it.path) }
    }

    /** Reports a change of [path]. */
    fun report(path: Path, kind: FileChangeKind = FileChangeKind.MODIFIED) {
        check(changes.tryEmit(FileChange(path.toAbsolutePath().normalize(), kind)))
    }
}

/** Workspaces on the virtual time of [scope]: every dispatcher is a test dispatcher, the clock a [TestTimeSource]. */
internal class TestWorkspaces(private val scope: TestScope) {
    /** The one test dispatcher every workspace of the test runs its I/O, analysis and sessions on. */
    val dispatcher = StandardTestDispatcher(scope.testScheduler)

    /** The fake watcher of every workspace of the test. */
    val watcher = FakeFileWatcher()

    /** Options on [dispatcher] and a fresh [TestTimeSource], watching only if [watch]. */
    fun options(watch: Boolean = false): WorkspaceOptions = WorkspaceOptions(
        watch = watch,
        ioDispatcher = dispatcher,
        analysisDispatcher = dispatcher,
        sessionConfig = DocumentSessionConfig(tokenizeDispatcher = dispatcher, parseDispatcher = dispatcher),
        timeSource = TestTimeSource(),
    )

    /** Opens [root] in the test's background scope, watching through [watcher] if [watch]. */
    fun open(root: Path, watch: Boolean = false, analyzer: ProjectAnalyzer? = null): Workspace {
        val languages = defaultLanguageRegistry()
        return Workspace(
            root, scope.backgroundScope, options(watch), languages,
            fileWatcher = watcher,
            analyzer = analyzer ?: ProjectAnalyzer(languages, DslConverter()),
        )
    }
}

/** Opens [path] in the workspace, failing the test if it does not open. */
internal suspend fun Workspace.openDocument(path: Path): OpenDocument =
    when (val result = documents.open(path)) {
        is OpenResult.Opened -> result.document
        else -> error("expected $path to open, was $result")
    }

/** What a problems view shows: the diagnostics per file, without snapshots and open flags. */
internal fun WorkspaceProblems.byFile(): Map<Path, List<Diagnostic>> = files.associate { it.path to it.diagnostics }
