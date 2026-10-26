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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [LayoutState]: the live layout's intents and restore; [isDark]. */
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
    fun `the Language Tools Output is hidden by default and shown on request`() {
        val layout = LayoutState()
        assertFalse(layout.state.value.toolWindow(ToolWindowId.OUTPUT).visible)
        assertEquals(ToolWindowId.entries - ToolWindowId.OUTPUT, ToolWindowId.entries.filter { it.visibleByDefault })
        assertFalse(LayoutUiState.of(IdeSettings()).toolWindow(ToolWindowId.OUTPUT).visible, "also without a stored layout")

        layout.showToolWindow(ToolWindowId.OUTPUT)
        assertTrue(layout.state.value.toolWindow(ToolWindowId.OUTPUT).visible)
        layout.showToolWindow(ToolWindowId.OUTPUT)
        assertTrue(layout.state.value.toolWindow(ToolWindowId.OUTPUT).visible, "showing a visible tool window keeps it")
        layout.toggleToolWindow(ToolWindowId.OUTPUT)
        assertFalse(layout.state.value.toolWindow(ToolWindowId.OUTPUT).visible)
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
    fun `system theme follows the operating system`() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
    }
}
