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
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import io.karpfen.ide.ui.workbench.settings.WindowBounds
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Window bounds from and to the layout, so the main window reopens where it was. */
class WindowBoundsTest {

    private val screen = ScreenArea(0, 0, 1920, 1080)

    private fun floating(x: Int?, y: Int?, width: Int, height: Int) = WindowGeometry(
        placement = WindowPlacement.Floating,
        isMinimized = false,
        position = if (x != null && y != null) WindowPosition(x.dp, y.dp) else WindowPosition.PlatformDefault,
        size = DpSize(width.dp, height.dp),
    )

    @Test
    fun `without persisted bounds the window opens centred with the default size`() {
        val state = initialWindowState(null, listOf(screen))

        assertEquals(WindowPlacement.Floating, state.placement)
        assertEquals(WindowPosition.Aligned(Alignment.Center), state.position)
        assertEquals(DEFAULT_WINDOW_SIZE, state.size)
    }

    @Test
    fun `persisted bounds restore position, size and the maximized placement`() {
        val floating = initialWindowState(WindowBounds(x = 100, y = 50, width = 1000, height = 700), listOf(screen))
        val maximized = initialWindowState(WindowBounds(x = 100, y = 50, width = 1000, height = 700, maximized = true), listOf(screen))

        assertEquals(WindowPosition(100.dp, 50.dp), floating.position)
        assertEquals(DpSize(1000.dp, 700.dp), floating.size)
        assertEquals(WindowPlacement.Floating, floating.placement)
        assertEquals(WindowPlacement.Maximized, maximized.placement)
        assertEquals(DpSize(1000.dp, 700.dp), maximized.size, "the restore size of the maximized window")
    }

    @Test
    fun `a tiny persisted size is enlarged and a position off every screen is dropped`() {
        val state = initialWindowState(WindowBounds(x = 5000, y = 40, width = 10, height = 10), listOf(screen))

        assertEquals(MINIMUM_WINDOW_SIZE, state.size)
        assertEquals(WindowPosition.Aligned(Alignment.Center), state.position)
    }

    @Test
    fun `a position on a second screen is kept and unknown screens skip the check`() {
        val right = ScreenArea(1920, 0, 2560, 1440)
        val bounds = WindowBounds(x = 2000, y = 100, width = 800, height = 600)

        assertEquals(WindowPosition(2000.dp, 100.dp), initialWindowState(bounds, listOf(screen, right)).position)
        assertEquals(WindowPosition(2000.dp, 100.dp), initialWindowState(bounds, emptyList()).position)
        assertEquals(WindowPosition.Aligned(Alignment.Center), initialWindowState(bounds.copy(x = null), listOf(screen)).position)
    }

    @Test
    fun `a title bar must be reachable on the screen`() {
        val size = DpSize(800.dp, 600.dp)

        assertEquals(true, screen.showsTitleBarOf(-700, 0, size), "100 dp of the window are on the screen")
        assertEquals(false, screen.showsTitleBarOf(-750, 0, size), "only 50 dp are on the screen")
        assertEquals(false, screen.showsTitleBarOf(100, -10, size), "the title bar is above the screen")
        assertEquals(false, screen.showsTitleBarOf(100, 1070, size), "the title bar is below the screen's usable height")
    }

    @Test
    fun `floating geometry is recorded in whole dp`() {
        val tracker = WindowBoundsTracker(restored = null)

        val bounds = tracker.boundsOf(floating(10, 20, 1000, 700).copy(size = DpSize(1000.4.dp, 699.6.dp)))

        assertEquals(WindowBounds(x = 10, y = 20, width = 1000, height = 700), bounds)
    }

    @Test
    fun `an unknown position is recorded without coordinates`() {
        assertEquals(WindowBounds(width = 900, height = 600), WindowBoundsTracker(null).boundsOf(floating(null, null, 900, 600)))
    }

    @Test
    fun `a maximized window is recorded as its last floating bounds with the maximized flag`() {
        val tracker = WindowBoundsTracker(restored = null)
        tracker.boundsOf(floating(10, 20, 1000, 700))

        val maximized = tracker.boundsOf(floating(0, 0, 1920, 1080).copy(placement = WindowPlacement.Maximized))
        val fullscreen = tracker.boundsOf(floating(0, 0, 1920, 1080).copy(placement = WindowPlacement.Fullscreen))

        assertEquals(WindowBounds(x = 10, y = 20, width = 1000, height = 700, maximized = true), maximized)
        assertEquals(maximized, fullscreen)
    }

    @Test
    fun `a window restored maximized keeps its persisted restore bounds`() {
        val restored = WindowBounds(x = 30, y = 40, width = 800, height = 600, maximized = true)
        val tracker = WindowBoundsTracker(restored)

        assertEquals(restored, tracker.boundsOf(floating(0, 0, 1920, 1080).copy(placement = WindowPlacement.Maximized)))
    }

    @Test
    fun `a maximized window without known floating bounds records its own size`() {
        val bounds = WindowBoundsTracker(null).boundsOf(floating(0, 0, 1920, 1080).copy(placement = WindowPlacement.Maximized))

        assertEquals(WindowBounds(x = 0, y = 0, width = 1920, height = 1080, maximized = true), bounds)
    }

    @Test
    fun `minimized windows and unknown sizes record nothing`() {
        val tracker = WindowBoundsTracker(null)

        assertNull(tracker.boundsOf(floating(1, 2, 300, 300).copy(isMinimized = true)))
        assertNull(tracker.boundsOf(floating(1, 2, 300, 300).copy(size = DpSize.Unspecified)))
    }

    @Test
    fun `a stream of geometries records each distinct bounds once`() = runTest {
        val geometries = flowOf(
            floating(10, 20, 1000, 700),
            floating(10, 20, 1000, 700),
            floating(10, 20, 1000, 700).copy(isMinimized = true),
            floating(10, 20, 1000, 700),
            floating(15, 20, 1000, 700),
        )

        val recorded = geometries.boundsToRecord(WindowBoundsTracker(null)).toList()

        assertEquals(
            listOf(WindowBounds(x = 10, y = 20, width = 1000, height = 700), WindowBounds(x = 15, y = 20, width = 1000, height = 700)),
            recorded,
        )
    }
}
