package io.karpfen.ide.app.e2e

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import io.karpfen.ide.app.IdeApplication
import io.karpfen.ide.app.IdeMenus
import io.karpfen.ide.app.IdeRunState
import io.karpfen.ide.app.IdeSession
import io.karpfen.ide.app.logging.LanguageToolsLogRouting
import io.karpfen.ide.app.menu.ResolvedMenuEntry
import io.karpfen.ide.app.menu.resolveMenus
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import io.karpfen.ide.ui.foundation.keys.compose.toKeyStroke
import io.karpfen.ide.ui.workbench.defaultWorkbenchServices
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.TestTimeSource

/**
 * The IDE as `main` wires it — `IdeApplication(…).prepare` over `defaultWorkbenchServices(…)` with the application's
 * output of the language tools and the real `WorkbenchState`, through the test seams of both composition roots — with
 * test values for everything time-, thread-, file-system- and toolkit-dependent: one test dispatcher on [scheduler] for
 * the UI scope, the workspace, the sessions, the editors' background work and the workbench's I/O; a [TestTimeSource]
 * for undo coalescing; a watcher that reports nothing; a fixed clock; recording file operations; settings below
 * [settingsDirectory]; [platform] as the platform of the keymap and the command registry, macOS unless a test chooses
 * another.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class EndToEndIde(
    val scheduler: TestCoroutineScheduler,
    settingsDirectory: Path,
    platform: HostPlatform = HostPlatform.MAC_OS,
) : AutoCloseable {
    /** The one test dispatcher on [scheduler] that runs all of the IDE's work. */
    val dispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler)

    /** The UI scope of the composition root. */
    val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob())

    /** The time source of the documents' undo coalescing. */
    val time = TestTimeSource()

    /** Records the desktop file operations instead of running them. */
    val fileOperations = RecordingFileOperations()

    private val workspaceOptions = WorkspaceOptions(
        ioDispatcher = dispatcher,
        analysisDispatcher = dispatcher,
        sessionConfig = DocumentSessionConfig(tokenizeDispatcher = dispatcher, parseDispatcher = dispatcher),
        timeSource = time,
    )

    private val application = IdeApplication(
        services = { languageToolsOutput ->
            defaultWorkbenchServices(
                settingsDirectory = settingsDirectory,
                platform = platform,
                backgroundDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                workspaceOptions = workspaceOptions,
                fileWatcher = SilentFileWatcher(),
                clock = FIXED_CLOCK,
                fileOperations = fileOperations,
                folderChooser = UnusedFolderChooser(),
                languageToolsOutput = languageToolsOutput,
            )
        },
    )

    private var prepared: IdeSession? = null

    /** The prepared session (fails before [prepare]). */
    val session: IdeSession get() = checkNotNull(prepared) { "prepare first" }

    /** `IdeApplication.prepare` with [folder] as the command-line folder; waits for the first analysis. */
    suspend fun prepare(folder: Path): IdeSession {
        val session = application.prepare(scope, folder)
        checkNotNull(session.state.workspace.value) { "prepare opened no workspace" }.awaitAnalysis()
        scheduler.advanceUntilIdle()
        prepared = session
        return session
    }

    /**
     * A started run of the application over this wiring with [folder] as the command-line folder: what
     * `IdeApplication.run` drives, without the window. The caller ends it (`IdeRunState.exit`).
     */
    fun startRun(folder: Path?): IdeRunState =
        IdeRunState(scope, prepare = { application.prepare(scope, folder) }).also { it.start() }

    /**
     * Routes what dsl-tools logs into the application's output of the language tools through [routing], as
     * `IdeApplication.run` does with the application's logger context, until the returned handle is closed.
     */
    fun routeLanguageToolsLog(routing: LanguageToolsLogRouting): AutoCloseable = application.routeLanguageToolsLog(routing)

    /** Workspaces like the CLI's (no watching) on the same virtual time, for `ValidateCommand`. */
    fun cliWorkspaces(): WorkspaceFactory = WorkspaceFactory(workspaceOptions.copy(watch = false))

    /** Runs everything due on the virtual clock. */
    fun settle() = scheduler.advanceUntilIdle()

    override fun close() {
        prepared?.state?.close()
        scope.cancel()
    }

    companion object {
        /** The clock of the settings backups. */
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-21T08:30:00Z"), ZoneOffset.UTC)
    }
}

/**
 * The window menu's accelerators without a window: the items of `resolveMenus(IdeMenus.specs(…), registry)` with their
 * bound shortcuts. [dispatch] does what the native menu bar does with a key stroke that no component consumed (the
 * focused component handles and consumes its keys first): it finds the item whose accelerator is the stroke and invokes
 * it through the registry. Installed as the outermost `onKeyEvent`, it sees exactly the unconsumed strokes and records
 * them in [unconsumed].
 */
internal class MenuAccelerators(registry: CommandRegistry, recentFolderCount: Int) {
    private val platform = registry.platform
    private val items: List<ResolvedMenuEntry.Item> =
        resolveMenus(IdeMenus.specs(recentFolderCount), registry).flatMap { menu -> menu.entries.flatMap(::itemsOf) }

    /** The key strokes no component consumed, whether an accelerator matched them or not. */
    val unconsumed: MutableList<KeyStroke> = mutableListOf()

    /** Command ids whose accelerator saw an unconsumed stroke. */
    val reached: MutableList<String> = mutableListOf()

    /** Command ids that ran from an accelerator (enabled at that moment). */
    val ran: MutableList<String> = mutableListOf()

    /** Handles an unconsumed key event; true if an accelerator matched. */
    fun dispatch(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val stroke = event.toKeyStroke() ?: return false
        unconsumed += stroke
        val item = items.firstOrNull { it.accelerator?.strokeFor(platform) == stroke } ?: return false
        reached += item.command.id
        if (item.invoke()) ran += item.command.id
        return true
    }

    /** The items of [entry], submenus flattened. */
    private fun itemsOf(entry: ResolvedMenuEntry): List<ResolvedMenuEntry.Item> = when (entry) {
        is ResolvedMenuEntry.Item -> listOf(entry)
        is ResolvedMenuEntry.Submenu -> entry.entries.flatMap(::itemsOf)
        ResolvedMenuEntry.Separator -> emptyList()
    }
}
