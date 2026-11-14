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
 * has a default size in dp, which is the width for a side window and the height for a bottom window. It says whether
 * it is [visibleByDefault], and whether it is [detachable], that is, whether its frame offers to move it into a window
 * of its own.
 *
 * A new tool window only needs a new entry here. The settings store the layout as a map keyed by [name], so the
 * settings format does not change. The id of its toggle command is `WorkbenchCommandIds.toggleToolWindow(id)`, and the
 * View menu iterates over the entries.
 */
public enum class ToolWindowId(
    public val title: String,
    public val defaultSize: Float,
    public val visibleByDefault: Boolean = true,
    public val detachable: Boolean = false,
) {
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
     * or starts a run. It is the one tool window that can be detached into a window of its own, so a run can be
     * watched on a second screen while the editor keeps the main window.
     */
    RUNTIME("Runtime", 400f, visibleByDefault = false, detachable = true),
    ;

    /** The layout of a tool window that nobody changed, with its default visibility and size. */
    public val defaultLayout: ToolWindowLayout get() = ToolWindowLayout(visible = visibleByDefault, size = defaultSize)
}

/**
 * The live window layout. It holds the visibility, size and place of each tool window, the theme, the bounds of the
 * main window and the bounds of the window of the detached Runtime tool window.
 *
 * A tool window is shown in one of two places. Docked, it has its place in the main window. Detached, it has a window
 * of its own and its docked place is closed. Visibility and place are independent: hiding a detached tool window
 * closes its window, and showing it again opens that window again.
 *
 * @property toolWindows the layout of every [ToolWindowId].
 * @property runtimeWindowBounds the last bounds of the window of the detached Runtime tool window, or null before it
 *   was ever placed.
 */
public data class LayoutUiState(
    public val toolWindows: Map<ToolWindowId, ToolWindowLayout> = ToolWindowId.entries.associateWith { it.defaultLayout },
    public val theme: ThemeMode = ThemeMode.SYSTEM,
    public val windowBounds: WindowBounds? = null,
    public val runtimeWindowBounds: WindowBounds? = null,
) {
    /** The layout of the tool window [id], or its default layout if none is stored. */
    public fun toolWindow(id: ToolWindowId): ToolWindowLayout = toolWindows[id] ?: id.defaultLayout

    /** True if the tool window [id] is shown in its place in the main window. */
    public fun isDocked(id: ToolWindowId): Boolean = toolWindow(id).let { it.visible && !it.detached }

    /** True if the tool window [id] is shown in a window of its own. */
    public fun isDetached(id: ToolWindowId): Boolean = toolWindow(id).let { it.visible && it.detached }

    /**
     * Returns [settings] with this layout written into it. The tool windows are stored under `ToolWindowId.name`.
     * Entries of tool windows this version does not know are kept.
     */
    public fun applyTo(settings: IdeSettings): IdeSettings = settings.copy(
        theme = theme,
        windowBounds = windowBounds,
        runtimeWindowBounds = runtimeWindowBounds,
        toolWindows = settings.toolWindows + toolWindows.mapKeys { (id, _) -> id.name },
    )

    public companion object {
        /**
         * The layout stored in [settings]. Unknown tool window keys are ignored. A tool window without a stored entry
         * gets its default layout. A tool window that is not [ToolWindowId.detachable] is read as docked, even if a
         * hand-edited project file says otherwise.
         */
        public fun of(settings: IdeSettings): LayoutUiState = LayoutUiState(
            toolWindows = ToolWindowId.entries.associateWith { id ->
                val stored = settings.toolWindows[id.name] ?: id.defaultLayout
                if (stored.detached && !id.detachable) stored.copy(detached = false) else stored
            },
            theme = settings.theme,
            windowBounds = settings.windowBounds,
            runtimeWindowBounds = settings.runtimeWindowBounds,
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

    private val raises = MutableStateFlow(0)

    @Volatile
    private var availableHeight: Float? = null

    /** The current layout. */
    public val state: StateFlow<LayoutUiState> get() = layout.asStateFlow()

    /**
     * Counts the requests to bring the window of the detached tool window to the front. [showToolWindow] and
     * [expandToolWindow] add one while the tool window they show is detached, because its window may lie behind the
     * main window, where showing it changes nothing visible. The window host brings the window to the front whenever
     * the count changes. The count is no setting and is not persisted, like the available height.
     */
    public val raiseRequests: StateFlow<Int> get() = raises.asStateFlow()

    /**
     * Shows or hides a tool window. The place stays as it is, so a detached tool window that is hidden and shown again
     * comes back in its own window.
     */
    public fun toggleToolWindow(id: ToolWindowId) {
        updateToolWindow(id) { it.copy(visible = !it.visible) }
    }

    /**
     * Shows a tool window. A visible tool window stays as it is. A detached tool window is shown in its own window,
     * which is brought to the front.
     */
    public fun showToolWindow(id: ToolWindowId) {
        updateToolWindow(id) { it.copy(visible = true) }
        raiseIfDetached(id)
    }

    /**
     * Shows a bottom tool window and gives it the [share] of the height that the tool windows and the editor have
     * together, for example three quarters for the Runtime tool window when a run starts. The height is the one the
     * layout view reported last through [reportAvailableHeight]. Before any report the tool window is only shown.
     *
     * A detached tool window is only shown and brought to the front. Its size is the size of its docked place, which
     * stays as it is, so docking it again restores the place it had.
     */
    public fun expandToolWindow(id: ToolWindowId, share: Float) {
        require(share > 0f && share < 1f) { "share must be between 0 and 1, was $share" }
        val height = availableHeight
        updateToolWindow(id) { current ->
            val dockedHeight = height.takeUnless { current.detached }
            val size = dockedHeight?.let { (it * share).coerceAtLeast(MIN_TOOL_WINDOW_SIZE) } ?: current.size
            current.copy(visible = true, size = size)
        }
        raiseIfDetached(id)
    }

    /**
     * Moves the tool window [id] between its place in the main window and a window of its own. Detaching shows the
     * tool window and closes its docked place, so the editor gets the room. Docking puts it back into its place with
     * the size it had. The button in the tool window's frame calls it.
     *
     * @throws IllegalArgumentException if [id] is not [ToolWindowId.detachable].
     */
    public fun toggleDetached(id: ToolWindowId) {
        require(id.detachable) { "${id.name} cannot be detached" }
        updateToolWindow(id) { it.copy(visible = true, detached = !it.detached) }
    }

    /**
     * Docks the tool window [id] again and leaves it shown. A docked tool window stays as it is. Closing the window of
     * a detached tool window with the window's own close button calls it, so closing that window never hides the tool
     * window.
     */
    public fun dockToolWindow(id: ToolWindowId) {
        updateToolWindow(id) { it.copy(detached = false) }
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

    /** Records the bounds of the window of the detached Runtime tool window. */
    public fun updateRuntimeWindowBounds(bounds: WindowBounds) {
        layout.update { it.copy(runtimeWindowBounds = bounds) }
    }

    /**
     * Replaces the layout with the one stored in [settings], as read by `LayoutUiState.of`. Only the mediator calls it.
     */
    internal fun restore(settings: IdeSettings) {
        layout.value = LayoutUiState.of(settings)
    }

    private fun raiseIfDetached(id: ToolWindowId) {
        if (layout.value.toolWindow(id).detached) raises.update { it + 1 }
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
