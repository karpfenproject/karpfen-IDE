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
