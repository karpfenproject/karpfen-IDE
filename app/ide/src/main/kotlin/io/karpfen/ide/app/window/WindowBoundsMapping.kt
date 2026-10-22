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

/** Size of the main window without persisted bounds (dp). */
internal val DEFAULT_WINDOW_SIZE: DpSize = DpSize(1280.dp, 800.dp)

/** Smallest restored window size (dp); smaller persisted sizes are enlarged to it. */
internal val MINIMUM_WINDOW_SIZE: DpSize = DpSize(480.dp, 320.dp)

/**
 * The main window's geometry as Compose reports it (a snapshot of the `WindowState`); positions and sizes in dp,
 * which are the window system's units on the desktop (AWT user space).
 */
internal data class WindowGeometry(
    val placement: WindowPlacement,
    val isMinimized: Boolean,
    val position: WindowPosition,
    val size: DpSize,
)

/** The current geometry of this window state (read inside `snapshotFlow` to observe it). */
internal fun WindowState.geometry(): WindowGeometry = WindowGeometry(placement, isMinimized, position, size)

/**
 * The window state the main window opens with, from the persisted [bounds] (`LayoutUiState.windowBounds`): maximized if
 * they were, the persisted size (at least [MINIMUM_WINDOW_SIZE]) and position. A position whose title bar would not be
 * reachable on any of [screens] is dropped, so a window saved on a disconnected monitor reopens centred; without bounds
 * the window opens centred with [DEFAULT_WINDOW_SIZE]. An empty [screens] list (unknown, e.g. headless) skips that
 * check.
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
 * Turns the window's geometry into the bounds the layout records (`LayoutState.updateWindowBounds`). Remembers the last
 * floating bounds, so a maximized or full-screen window is recorded as those bounds with `maximized = true` and
 * restores to them; a minimized window and a geometry without a known size record nothing. Used on the UI thread.
 *
 * @param restored the bounds the window opened with (the restore bounds until the window floats).
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

    /** The bounds of a floating window of [geometry]; an unspecified position is recorded as none. */
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

/** The distinct bounds to record for this stream of window geometries (see [WindowBoundsTracker]). */
internal fun Flow<WindowGeometry>.boundsToRecord(tracker: WindowBoundsTracker): Flow<WindowBounds> =
    mapNotNull(tracker::boundsOf).distinctUntilChanged()
