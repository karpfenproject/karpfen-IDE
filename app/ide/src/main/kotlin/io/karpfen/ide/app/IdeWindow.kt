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
package io.karpfen.ide.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import io.karpfen.ide.app.window.ScreenAreas
import io.karpfen.ide.app.window.WindowBoundsTracker
import io.karpfen.ide.app.window.boundsToRecord
import io.karpfen.ide.app.window.geometry
import io.karpfen.ide.app.window.initialWindowState
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.Workbench
import io.karpfen.ide.ui.workbench.layout.LayoutState
import io.karpfen.ide.ui.workbench.layout.isDark
import kotlinx.coroutines.flow.StateFlow

/**
 * The application's content for [runState]'s phase: nothing while the session is prepared, the main window while it
 * runs, and the end of the application once the run exited. Rendering only; the lifecycle is [IdeRunState]'s.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ApplicationScope.IdeApplicationContent(
    runState: IdeRunState,
    windowExceptions: WindowExceptionHandlerFactory,
    screens: ScreenAreas,
) {
    when (val phase = runState.phase.collectAsState().value) {
        IdePhase.Starting -> Unit
        is IdePhase.Running -> IdeMainWindow(
            phase.session,
            runState.stoppingRuntime,
            onCloseRequest = { runState.requestClose() },
            windowExceptions,
            screens,
        )
        IdePhase.Exited -> SideEffect { exitApplication() }
    }
}

/**
 * The main window of [session]: title from `WorkbenchState.windowTitle`, bounds from and to `WorkbenchState.layout`,
 * the menu bar from [IdeMenus] (with the current number of recent folders), the [Workbench] as content with the
 * [StoppingRuntimeNote] over it while [stoppingRuntime] is true, close requests to [onCloseRequest], and failures
 * inside the window to [windowExceptions] (instead of Compose's default, which closes the window).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun IdeMainWindow(
    session: IdeSession,
    stoppingRuntime: StateFlow<Boolean>,
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
    val stopping by stoppingRuntime.collectAsState()
    val menus = remember(settings.recentFolders.size) { IdeMenus.specs(settings.recentFolders.size) }
    CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides windowExceptions) {
        Window(onCloseRequest = onCloseRequest, state = windowState, title = title) {
            IdeMenuBar(session.commands, menus)
            Box(Modifier.fillMaxSize()) {
                Workbench(state)
                if (stopping) StoppingRuntimeNote(state.layout, Modifier.align(Alignment.Center))
            }
        }
    }
}

/** The short note that the runtime is being stopped on exit, in the theme of the workbench's [layout]. */
@Composable
private fun StoppingRuntimeNote(layout: LayoutState, modifier: Modifier) {
    val theme by layout.state.collectAsState()
    KarpfenTheme(darkTheme = theme.theme.isDark(isSystemInDarkTheme())) {
        Text(
            IdeRunState.STOPPING_RUNTIME_NOTE,
            modifier
                .background(KarpfenTheme.colors.panel)
                .border(1.dp, KarpfenTheme.colors.border)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            color = KarpfenTheme.colors.text,
            style = KarpfenTheme.typography.ui,
        )
    }
}
