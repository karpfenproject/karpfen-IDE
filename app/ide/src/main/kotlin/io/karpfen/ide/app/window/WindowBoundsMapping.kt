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

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import io.karpfen.ide.ui.workbench.settings.WindowBounds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlin.math.roundToInt

/** Size of the main window without persisted bounds, in dp. */
internal val DEFAULT_WINDOW_SIZE: DpSize = DpSize(1280.dp, 800.dp)

/** Smallest restored window size, in dp. Smaller persisted sizes are enlarged to it. */
internal val MINIMUM_WINDOW_SIZE: DpSize = DpSize(480.dp, 320.dp)

/**
 * The main window's geometry as Compose reports it, as a snapshot of the `WindowState`. Positions and sizes are in dp,
 * which are the window system's units on the desktop, the AWT user space.
 */
internal data class WindowGeometry(
    val placement: WindowPlacement,
    val isMinimized: Boolean,
    val position: WindowPosition,
    val size: DpSize,
)

/** The current geometry of this window state. Read it inside `snapshotFlow` to observe it. */
internal fun WindowState.geometry(): WindowGeometry = WindowGeometry(placement, isMinimized, position, size)

/**
 * The window state the main window opens with, from the persisted [bounds], which are `LayoutUiState.windowBounds`.
 * The window is maximized if it was, and it gets the persisted position and the persisted size, at least
 * [MINIMUM_WINDOW_SIZE]. A position whose title bar would not be reachable on any of [screens] is dropped, so a window
 * saved on a disconnected monitor reopens centred. Without bounds the window opens centred with [DEFAULT_WINDOW_SIZE].
 * An empty [screens] list means the screens are unknown, as in a headless JVM, and skips the check.
 */
internal fun initialWindowState(bounds: WindowBounds?, screens: List<ScreenArea>): WindowState {
    if (bounds == null) {
        return WindowState(position = WindowPosition.Aligned(Alignment.Center), size = DEFAULT_WINDOW_SIZE)
    }
    val size = DpSize(
        bounds.width.coerceAtLeast(MINIMUM_WINDOW_SIZE.width.value.roundToInt()).dp,
        bounds.height.coerceAtLeast(MINIMUM_WINDOW_SIZE.height.value.roundToInt()).dp,
    )
    val x = bounds.x
    val y = bounds.y
    val position = if (x != null && y != null && (screens.isEmpty() || screens.any { it.showsTitleBarOf(x, y, size) })) {
        WindowPosition(x.dp, y.dp)
    } else {
        WindowPosition.Aligned(Alignment.Center)
    }
    val placement = if (bounds.maximized) WindowPlacement.Maximized else WindowPlacement.Floating
    return WindowState(placement = placement, position = position, size = size)
}

/**
 * Turns the window's geometry into the bounds the layout records through `LayoutState.updateWindowBounds`. It
 * remembers the last floating bounds. So a maximized or full-screen window is recorded as those bounds with
 * `maximized = true`, and it restores to them. A minimized window and a geometry without a known size record nothing.
 * Use it on the UI thread.
 *
 * @param restored the bounds the window opened with. They serve as the restore bounds until the window floats.
 */
internal class WindowBoundsTracker(restored: WindowBounds?) {
    private var floating: WindowBounds? = restored?.copy(maximized = false)

    /** The bounds to record for [geometry], or null if there is nothing to record. */
    fun boundsOf(geometry: WindowGeometry): WindowBounds? {
        if (geometry.isMinimized || !geometry.size.isSpecified) return null
        return when (geometry.placement) {
            WindowPlacement.Floating -> floatingBoundsOf(geometry).also { floating = it }
            WindowPlacement.Maximized, WindowPlacement.Fullscreen -> (floating ?: floatingBoundsOf(geometry)).copy(maximized = true)
        }
    }

    /** The bounds of a floating window of [geometry]. An unspecified position is recorded as no position. */
    private fun floatingBoundsOf(geometry: WindowGeometry): WindowBounds {
        val position = geometry.position.takeIf { it.isSpecified && it.x.isSpecified && it.y.isSpecified }
        return WindowBounds(
            x = position?.x?.value?.roundToInt(),
            y = position?.y?.value?.roundToInt(),
            width = geometry.size.width.value.roundToInt(),
            height = geometry.size.height.value.roundToInt(),
        )
    }
}

/** The distinct bounds to record for this stream of window geometries, as [WindowBoundsTracker] decides them. */
internal fun Flow<WindowGeometry>.boundsToRecord(tracker: WindowBoundsTracker): Flow<WindowBounds> =
    mapNotNull(tracker::boundsOf).distinctUntilChanged()
