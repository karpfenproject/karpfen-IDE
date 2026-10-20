package io.karpfen.ide.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import io.karpfen.ide.app.window.ScreenAreas
import io.karpfen.ide.app.window.WindowBoundsTracker
import io.karpfen.ide.app.window.boundsToRecord
import io.karpfen.ide.app.window.geometry
import io.karpfen.ide.app.window.initialWindowState
import io.karpfen.ide.ui.workbench.Workbench

/**
 * The application's content for [runner]'s phase: nothing while the session is prepared, the main window while it
 * runs, and the end of the application once the run exited. Rendering only; the lifecycle is [IdeRunner]'s.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ApplicationScope.IdeApplicationContent(
    runner: IdeRunner,
    windowExceptions: WindowExceptionHandlerFactory,
    screens: ScreenAreas,
) {
    when (val phase = runner.phase.collectAsState().value) {
        IdePhase.Starting -> Unit
        is IdePhase.Running -> IdeMainWindow(phase.session, onCloseRequest = { runner.requestClose() }, windowExceptions, screens)
        IdePhase.Exited -> SideEffect { exitApplication() }
    }
}

/**
 * The main window of [session] (spec §9): title from `WorkbenchState.windowTitle`, bounds from and to
 * `WorkbenchState.layout`, the menu bar from [IdeMenus] (with the current number of recent folders), the [Workbench]
 * as content, close requests to [onCloseRequest], and failures inside the window to [windowExceptions] (instead of
 * Compose's default, which closes the window).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun IdeMainWindow(
    session: IdeSession,
    onCloseRequest: () -> Unit,
    windowExceptions: WindowExceptionHandlerFactory,
    screens: ScreenAreas,
) {
    val state = session.state
    val title by state.windowTitle.collectAsState()
    val settings by state.settings.collectAsState()
    val restoredBounds = remember(state) { state.layout.state.value.windowBounds }
    val windowState = remember(state) { initialWindowState(restoredBounds, screens.current()) }
    val boundsTracker = remember(state) { WindowBoundsTracker(restoredBounds) }
    LaunchedEffect(windowState) {
        snapshotFlow { windowState.geometry() }.boundsToRecord(boundsTracker).collect(state.layout::updateWindowBounds)
    }
    val menus = remember(settings.recentFolders.size) { IdeMenus.specs(settings.recentFolders.size) }
    CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides windowExceptions) {
        Window(onCloseRequest = onCloseRequest, state = windowState, title = title) {
            IdeMenuBar(session.commands, menus)
            Workbench(state)
        }
    }
}
