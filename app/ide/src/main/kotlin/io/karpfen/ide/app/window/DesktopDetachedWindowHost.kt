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
package io.karpfen.ide.app.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.FrameWindowScope
import io.karpfen.ide.ui.workbench.layout.view.DetachedWindow
import io.karpfen.ide.ui.workbench.layout.view.DetachedWindowHost
import androidx.compose.ui.window.Window as DesktopWindow

/**
 * The [DetachedWindowHost] of the desktop. It opens an operating system window with the application's [icon] and the
 * same menu bar as the main window, drawn by [menuBar]. The menu bar is there because the commands and their shortcuts
 * reach the IDE through the menu bar of the focused window, and because macOS shows the menu bar of the focused window
 * at the top of the screen.
 *
 * The window opens with the persisted bounds, placed and checked against [screens] like the main window, or centred
 * with [DEFAULT_DETACHED_WINDOW_SIZE] the first time. Its bounds are recorded through [WindowBoundsTracker] whenever the
 * user moves or resizes it. A change of [DetachedWindow.raiseRequests] brings it to the front. The window closes when
 * the workbench stops composing it, which happens when the tool window docks or is hidden, when the project closes and
 * when the main window closes.
 */
internal class DesktopDetachedWindowHost(
    private val screens: ScreenAreas,
    private val icon: Painter?,
    private val menuBar: @Composable FrameWindowScope.() -> Unit,
) : DetachedWindowHost {

    @Composable
    override fun Window(window: DetachedWindow, content: @Composable () -> Unit) {
        val windowState = remember { initialWindowState(window.initialBounds, screens.current(), DEFAULT_DETACHED_WINDOW_SIZE) }
        val tracker = remember { WindowBoundsTracker(window.initialBounds) }
        val onBoundsChange by rememberUpdatedState(window.onBoundsChange)
        val onCloseRequest by rememberUpdatedState(window.onCloseRequest)
        LaunchedEffect(windowState) {
            snapshotFlow { windowState.geometry() }.boundsToRecord(tracker).collect { onBoundsChange(it) }
        }
        DesktopWindow(onCloseRequest = { onCloseRequest() }, state = windowState, title = window.title, icon = icon) {
            val frame: FrameWindowScope = this
            menuBar()
            LaunchedEffect(window.raiseRequests) { frame.window.toFront() }
            content()
        }
    }
}
