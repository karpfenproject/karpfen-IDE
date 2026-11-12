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
 * The tool windows around the editor area. Each has a [title], which the tool window header and the View menu show. It
 * has a default size in dp, which is the width for a side window and the height for a bottom window. And it says
 * whether it is [visibleByDefault].
 *
 * A new tool window only needs a new entry here. The settings store the layout as a map keyed by [name], so the
 * settings format does not change. The id of its toggle command is `WorkbenchCommandIds.toggleToolWindow(id)`, and the
 * View menu iterates over the entries.
 */
public enum class ToolWindowId(public val title: String, public val defaultSize: Float, public val visibleByDefault: Boolean = true) {
    /** The project tree, on the left. */
    PROJECT("Project", 260f),

    /** The outline of the active document, on the right. */
    OUTLINE("Outline", 240f),

    /**
     * The problems, at the bottom. It is hidden until the user opens it from the status bar or the View menu, and the
     * workbench hides it again whenever there is no problem to show.
     */
    PROBLEMS("Problems", 200f, visibleByDefault = false),

    /** What the language tools log, at the bottom below Problems. It is hidden until the user opens it. */
    OUTPUT("Language Tools Output", 180f, visibleByDefault = false),

    /**
     * The runtime and the runs of the workspace, at the bottom below the output. It is hidden until the user opens it
     * or starts a run.
     */
    RUNTIME("Runtime", 400f, visibleByDefault = false),
    ;

    /** The layout of a tool window that nobody changed, with its default visibility and size. */
    public val defaultLayout: ToolWindowLayout get() = ToolWindowLayout(visible = visibleByDefault, size = defaultSize)
}

/**
 * The live window layout. It holds the visibility and size of each tool window, the theme and the bounds of the main
 * window.
 *
 * @property toolWindows the layout of every [ToolWindowId].
 */
public data class LayoutUiState(
    public val toolWindows: Map<ToolWindowId, ToolWindowLayout> = ToolWindowId.entries.associateWith { it.defaultLayout },
    public val theme: ThemeMode = ThemeMode.SYSTEM,
    public val windowBounds: WindowBounds? = null,
) {
    /** The layout of the tool window [id], or its default layout if none is stored. */
    public fun toolWindow(id: ToolWindowId): ToolWindowLayout = toolWindows[id] ?: id.defaultLayout

    /**
     * Returns [settings] with this layout written into it. The tool windows are stored under `ToolWindowId.name`.
     * Entries of tool windows this version does not know are kept.
     */
    public fun applyTo(settings: IdeSettings): IdeSettings = settings.copy(
        theme = theme,
        windowBounds = windowBounds,
        toolWindows = settings.toolWindows + toolWindows.mapKeys { (id, _) -> id.name },
    )

    public companion object {
        /**
         * The layout stored in [settings]. Unknown tool window keys are ignored. A tool window without a stored entry
         * gets its default layout.
         */
        public fun of(settings: IdeSettings): LayoutUiState = LayoutUiState(
            toolWindows = ToolWindowId.entries.associateWith { id -> settings.toolWindows[id.name] ?: id.defaultLayout },
            theme = settings.theme,
            windowBounds = settings.windowBounds,
        )
    }
}

/**
 * The window layout as live UI state. It is a sub-state of the workbench and does not depend on Compose. Its intents
 * change only this state, and they may be called at any rate, for example on every frame of a split pane drag.
 *
 * Persistence is the job of the workbench mediator. It writes [state] into the settings through the
 * `SettingsRepository`, debounced, and once more in `requestExit`. After loading the settings it calls [restore].
 */
public class LayoutState {

    private val layout = MutableStateFlow(LayoutUiState())

    @Volatile
    private var availableHeight: Float? = null

    /** The current layout. */
    public val state: StateFlow<LayoutUiState> get() = layout.asStateFlow()

    /** Shows or hides a tool window. */
    public fun toggleToolWindow(id: ToolWindowId) {
        updateToolWindow(id) { it.copy(visible = !it.visible) }
    }

    /** Shows a tool window. A visible tool window stays as it is. */
    public fun showToolWindow(id: ToolWindowId) {
        updateToolWindow(id) { it.copy(visible = true) }
    }

    /**
     * Shows a bottom tool window and gives it the [share] of the height that the tool windows and the editor have
     * together, for example three quarters for the Runtime tool window when a run starts. The height is the one the
     * layout view reported last through [reportAvailableHeight]. Before any report the tool window is only shown.
     */
    public fun expandToolWindow(id: ToolWindowId, share: Float) {
        require(share > 0f && share < 1f) { "share must be between 0 and 1, was $share" }
        val height = availableHeight
        updateToolWindow(id) { current ->
            val size = height?.let { (it * share).coerceAtLeast(MIN_TOOL_WINDOW_SIZE) } ?: current.size
            current.copy(visible = true, size = size)
        }
    }

    /**
     * Records the height in dp that the tool windows and the editor have together. The layout view calls it whenever
     * that height changes. The height is not part of [state], because it is no setting and is not persisted.
     */
    public fun reportAvailableHeight(height: Float) {
        if (height > 0f) availableHeight = height
    }

    /** Hides a tool window. A hidden tool window stays as it is. */
    public fun hideToolWindow(id: ToolWindowId) {
        updateToolWindow(id) { it.copy(visible = false) }
    }

    /**
     * Sets the size of a tool window in dp and clamps it to [MIN_TOOL_WINDOW_SIZE]. A NaN size also becomes the
     * minimum. The size is the width for Project and Outline and the height for Problems, Output and Runtime. The
     * workbench passes the `Dp.value` from the `onFixedSizeChange` callback of the size-based `SplitPane` unchanged, so
     * the composable converts nothing.
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

    /**
     * Replaces the layout with the one stored in [settings], as read by `LayoutUiState.of`. Only the mediator calls it.
     */
    internal fun restore(settings: IdeSettings) {
        layout.value = LayoutUiState.of(settings)
    }

    private fun updateToolWindow(id: ToolWindowId, change: (ToolWindowLayout) -> ToolWindowLayout) {
        layout.update { current -> current.copy(toolWindows = current.toolWindows + (id to change(current.toolWindow(id)))) }
    }

    public companion object {
        /** The smallest size of a tool window in dp. [resizeToolWindow] clamps to it. */
        public const val MIN_TOOL_WINDOW_SIZE: Float = 80f
    }
}

/** True if this theme renders dark. [systemDark] tells whether the operating system currently uses a dark theme. */
public fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> systemDark
}
