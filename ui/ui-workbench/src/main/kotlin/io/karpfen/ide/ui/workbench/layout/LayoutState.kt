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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The tool windows around the editor area, with their [title] (tool-window header and View menu), default size in dp
 * (width for side windows, height for bottom ones) and whether they are [visibleByDefault]. A new tool window is a new
 * entry here: the persisted form is a map by [name] (no schema change), the toggle command id is
 * `WorkbenchCommandIds.toggleToolWindow(id)` and the View menu iterates the entries.
 */
public enum class ToolWindowId(public val title: String, public val defaultSize: Float, public val visibleByDefault: Boolean = true) {
    /** Project tree (left). */
    PROJECT("Project", 260f),

    /** Outline of the active document (right). */
    OUTLINE("Outline", 240f),

    /** Problems (bottom). */
    PROBLEMS("Problems", 200f),

    /** What the language tools log (bottom, below Problems); hidden until the user opens it. */
    OUTPUT("Language Tools Output", 180f, visibleByDefault = false),

    /** The runtime and the runs of the workspace (bottom, below the output); hidden until the user opens it or runs. */
    RUNTIME("Runtime", 320f, visibleByDefault = false),
    ;

    /** The layout of a tool window nobody changed: its default visibility and size. */
    public val defaultLayout: ToolWindowLayout get() = ToolWindowLayout(visible = visibleByDefault, size = defaultSize)
}

/**
 * The live window layout: tool-window visibility and sizes, the theme and the main window's bounds.
 *
 * @property toolWindows the layout of every [ToolWindowId].
 */
public data class LayoutUiState(
    public val toolWindows: Map<ToolWindowId, ToolWindowLayout> = ToolWindowId.entries.associateWith { it.defaultLayout },
    public val theme: ThemeMode = ThemeMode.SYSTEM,
    public val windowBounds: WindowBounds? = null,
) {
    /** The layout of [id] (its default if absent). */
    public fun toolWindow(id: ToolWindowId): ToolWindowLayout = toolWindows[id] ?: id.defaultLayout

    /** [settings] with this layout written into it (tool windows by `ToolWindowId.name`). */
    public fun applyTo(settings: IdeSettings): IdeSettings = settings.copy(
        theme = theme,
        windowBounds = windowBounds,
        toolWindows = settings.toolWindows + toolWindows.mapKeys { (id, _) -> id.name },
    )

    public companion object {
        /** The layout stored in [settings]; unknown keys are ignored, missing tool windows take their default. */
        public fun of(settings: IdeSettings): LayoutUiState = LayoutUiState(
            toolWindows = ToolWindowId.entries.associateWith { id -> settings.toolWindows[id.name] ?: id.defaultLayout },
            theme = settings.theme,
            windowBounds = settings.windowBounds,
        )
    }
}

/**
 * The window layout as live UI state: a Compose-free sub-state of the workbench. Its intents change only this state, at
 * any rate (e.g. every frame of a split-pane drag); persistence is the mediator's job: it writes [state] into the
 * settings through the `SettingsRepository` debounced and once more in `requestExit`, and restores it after loading the
 * settings ([restore]).
 */
public class LayoutState {

    private val layout = MutableStateFlow(LayoutUiState())

    /** The current layout. */
    public val state: StateFlow<LayoutUiState> get() = layout.asStateFlow()

    /** Shows or hides a tool window. */
    public fun toggleToolWindow(id: ToolWindowId) {
        updateToolWindow(id) { it.copy(visible = !it.visible) }
    }

    /** Shows a tool window; a visible one stays as it is. */
    public fun showToolWindow(id: ToolWindowId) {
        updateToolWindow(id) { it.copy(visible = true) }
    }

    /**
     * Sets a tool window's size in dp (width for PROJECT/OUTLINE, height for PROBLEMS/OUTPUT/RUNTIME), clamped to a minimum.
     * The workbench passes the value of the size-based `SplitPane`'s `onFixedSizeChange` unchanged (`Dp.value`), so the
     * composable converts nothing.
     */
    public fun resizeToolWindow(id: ToolWindowId, size: Float) {
        val clamped = if (size.isNaN()) MIN_TOOL_WINDOW_SIZE else size.coerceAtLeast(MIN_TOOL_WINDOW_SIZE)
        updateToolWindow(id) { it.copy(size = clamped) }
    }

    /** Switches the theme. */
    public fun setTheme(mode: ThemeMode) {
        layout.update { it.copy(theme = mode) }
    }

    /** Records the main window's bounds. */
    public fun updateWindowBounds(bounds: WindowBounds) {
        layout.update { it.copy(windowBounds = bounds) }
    }

    /** Replaces the layout by the one stored in [settings] (`LayoutUiState.of`). Mediator only. */
    internal fun restore(settings: IdeSettings) {
        layout.value = LayoutUiState.of(settings)
    }

    private fun updateToolWindow(id: ToolWindowId, change: (ToolWindowLayout) -> ToolWindowLayout) {
        layout.update { current -> current.copy(toolWindows = current.toolWindows + (id to change(current.toolWindow(id)))) }
    }

    public companion object {
        /** The smallest size of a tool window in dp; [resizeToolWindow] clamps to it. */
        public const val MIN_TOOL_WINDOW_SIZE: Float = 80f
    }
}

/** True if this theme renders dark, given whether the operating system currently uses a dark theme ([systemDark]). */
public fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> systemDark
}
