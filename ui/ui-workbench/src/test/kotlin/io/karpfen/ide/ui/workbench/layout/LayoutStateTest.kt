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
package io.karpfen.ide.ui.workbench.layout

import io.karpfen.ide.ui.workbench.settings.IdeSettings
import io.karpfen.ide.ui.workbench.settings.ThemeMode
import io.karpfen.ide.ui.workbench.settings.ToolWindowLayout
import io.karpfen.ide.ui.workbench.settings.WindowBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests the intents of the live [LayoutState], restoring it from the settings, and [isDark]. */
class LayoutStateTest {

    @Test
    fun `intents change the live layout`() {
        val layout = LayoutState()
        assertEquals(LayoutUiState(), layout.state.value)

        layout.toggleToolWindow(ToolWindowId.OUTLINE)
        assertFalse(layout.state.value.toolWindow(ToolWindowId.OUTLINE).visible)
        layout.toggleToolWindow(ToolWindowId.OUTLINE)
        assertTrue(layout.state.value.toolWindow(ToolWindowId.OUTLINE).visible)

        layout.resizeToolWindow(ToolWindowId.PROBLEMS, 321f)
        assertEquals(321f, layout.state.value.toolWindow(ToolWindowId.PROBLEMS).size)
        layout.resizeToolWindow(ToolWindowId.PROBLEMS, 3f)
        assertEquals(LayoutState.MIN_TOOL_WINDOW_SIZE, layout.state.value.toolWindow(ToolWindowId.PROBLEMS).size)
        layout.resizeToolWindow(ToolWindowId.PROBLEMS, Float.NaN)
        assertEquals(LayoutState.MIN_TOOL_WINDOW_SIZE, layout.state.value.toolWindow(ToolWindowId.PROBLEMS).size)

        layout.setTheme(ThemeMode.DARK)
        layout.updateWindowBounds(WindowBounds(x = 1, y = 2, width = 800, height = 600))
        assertEquals(ThemeMode.DARK, layout.state.value.theme)
        assertEquals(WindowBounds(1, 2, 800, 600), layout.state.value.windowBounds)
    }

    @Test
    fun `Problems, the Language Tools Output and the Runtime are hidden by default and shown on request`() {
        val layout = LayoutState()
        assertFalse(layout.state.value.toolWindow(ToolWindowId.OUTPUT).visible)
        assertEquals(
            ToolWindowId.entries - ToolWindowId.PROBLEMS - ToolWindowId.OUTPUT - ToolWindowId.RUNTIME,
            ToolWindowId.entries.filter { it.visibleByDefault },
        )
        assertFalse(LayoutUiState.of(IdeSettings()).toolWindow(ToolWindowId.OUTPUT).visible, "also without a stored layout")

        layout.showToolWindow(ToolWindowId.OUTPUT)
        assertTrue(layout.state.value.toolWindow(ToolWindowId.OUTPUT).visible)
        layout.showToolWindow(ToolWindowId.OUTPUT)
        assertTrue(layout.state.value.toolWindow(ToolWindowId.OUTPUT).visible, "showing a visible tool window keeps it")
        layout.toggleToolWindow(ToolWindowId.OUTPUT)
        assertFalse(layout.state.value.toolWindow(ToolWindowId.OUTPUT).visible)
    }

    @Test
    fun `expanding a tool window shows it and gives it a share of the reported height`() {
        val layout = LayoutState()

        layout.expandToolWindow(ToolWindowId.RUNTIME, 0.75f)
        assertTrue(layout.state.value.toolWindow(ToolWindowId.RUNTIME).visible)
        assertEquals(ToolWindowId.RUNTIME.defaultSize, layout.state.value.toolWindow(ToolWindowId.RUNTIME).size, "no height reported yet")

        layout.reportAvailableHeight(800f)
        layout.expandToolWindow(ToolWindowId.RUNTIME, 0.75f)
        assertEquals(600f, layout.state.value.toolWindow(ToolWindowId.RUNTIME).size)

        layout.reportAvailableHeight(60f)
        layout.expandToolWindow(ToolWindowId.RUNTIME, 0.75f)
        assertEquals(LayoutState.MIN_TOOL_WINDOW_SIZE, layout.state.value.toolWindow(ToolWindowId.RUNTIME).size, "never below the minimum")
    }

    @Test
    fun `restore takes the layout from the settings`() {
        val layout = LayoutState()
        layout.toggleToolWindow(ToolWindowId.PROJECT)
        val settings = IdeSettings(theme = ThemeMode.LIGHT, toolWindows = mapOf("OUTLINE" to ToolWindowLayout(size = 111f)))
        layout.restore(settings)
        assertEquals(LayoutUiState.of(settings), layout.state.value)
        assertTrue(layout.state.value.toolWindow(ToolWindowId.PROJECT).visible)
    }

    @Test
    fun `only the Runtime tool window is detachable`() {
        assertEquals(listOf(ToolWindowId.RUNTIME), ToolWindowId.entries.filter { it.detachable })
        assertFailsWith<IllegalArgumentException> { LayoutState().toggleDetached(ToolWindowId.PROJECT) }
    }

    @Test
    fun `detaching moves the tool window out of its docked place and docking puts it back with its size`() {
        val layout = LayoutState()
        val runtime = ToolWindowId.RUNTIME
        layout.resizeToolWindow(runtime, 333f)

        layout.toggleDetached(runtime)
        assertTrue(layout.state.value.isDetached(runtime), "detaching shows the hidden tool window in its own window")
        assertFalse(layout.state.value.isDocked(runtime))

        layout.toggleDetached(runtime)
        assertTrue(layout.state.value.isDocked(runtime))
        assertFalse(layout.state.value.isDetached(runtime))
        assertEquals(333f, layout.state.value.toolWindow(runtime).size)

        layout.toggleDetached(runtime)
        layout.dockToolWindow(runtime)
        assertTrue(layout.state.value.isDocked(runtime), "closing the detached window docks it and keeps it shown")
        layout.dockToolWindow(runtime)
        assertTrue(layout.state.value.isDocked(runtime), "docking a docked tool window keeps it as it is")
    }

    @Test
    fun `hiding a detached tool window keeps its place, so showing it again opens its window again`() {
        val layout = LayoutState()
        val runtime = ToolWindowId.RUNTIME
        layout.toggleDetached(runtime)

        layout.toggleToolWindow(runtime)
        assertFalse(layout.state.value.isDetached(runtime))
        assertFalse(layout.state.value.isDocked(runtime))
        assertTrue(layout.state.value.toolWindow(runtime).detached)

        layout.toggleToolWindow(runtime)
        assertTrue(layout.state.value.isDetached(runtime))
    }

    @Test
    fun `showing or expanding a detached tool window raises its window and leaves the docked size alone`() {
        val layout = LayoutState()
        val runtime = ToolWindowId.RUNTIME
        layout.reportAvailableHeight(800f)
        layout.showToolWindow(runtime)
        layout.expandToolWindow(runtime, 0.75f)
        assertEquals(0, layout.raiseRequests.value, "a docked tool window has no window to raise")
        assertEquals(600f, layout.state.value.toolWindow(runtime).size)

        layout.resizeToolWindow(runtime, 250f)
        layout.toggleDetached(runtime)
        layout.expandToolWindow(runtime, 0.75f)
        assertEquals(1, layout.raiseRequests.value)
        assertEquals(250f, layout.state.value.toolWindow(runtime).size)
        assertTrue(layout.state.value.isDetached(runtime))

        layout.toggleToolWindow(runtime)
        layout.showToolWindow(runtime)
        assertEquals(2, layout.raiseRequests.value)
        assertTrue(layout.state.value.isDetached(runtime), "showing a hidden detached tool window opens its window")

        layout.showToolWindow(ToolWindowId.OUTPUT)
        assertEquals(2, layout.raiseRequests.value, "showing another tool window raises nothing")
    }

    @Test
    fun `the detached place and the bounds of its window are read from and written to the settings`() {
        val bounds = WindowBounds(x = 900, y = 30, width = 960, height = 600)
        val settings = IdeSettings(
            runtimeWindowBounds = bounds,
            toolWindows = mapOf(
                "RUNTIME" to ToolWindowLayout(visible = true, size = 400f, detached = true),
                "PROBLEMS" to ToolWindowLayout(visible = true, size = 200f, detached = true),
            ),
        )
        val read = LayoutUiState.of(settings)
        assertTrue(read.isDetached(ToolWindowId.RUNTIME))
        assertEquals(bounds, read.runtimeWindowBounds)
        assertTrue(read.isDocked(ToolWindowId.PROBLEMS), "a tool window that is not detachable is read as docked")

        val layout = LayoutState()
        val moved = WindowBounds(x = 10, y = 20, width = 700, height = 500)
        layout.updateRuntimeWindowBounds(moved)
        layout.toggleDetached(ToolWindowId.RUNTIME)
        val written = layout.state.value.applyTo(IdeSettings())
        assertEquals(moved, written.runtimeWindowBounds)
        assertEquals(true, written.toolWindows["RUNTIME"]?.detached)
        assertEquals(layout.state.value, LayoutUiState.of(written))
    }

    @Test
    fun `system theme follows the operating system`() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
    }
}
