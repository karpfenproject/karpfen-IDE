package io.karpfen.ide.ui.workbench.layout

import io.karpfen.ide.ui.workbench.settings.IdeSettings
import io.karpfen.ide.ui.workbench.settings.ThemeMode
import io.karpfen.ide.ui.workbench.settings.ToolWindowLayout
import io.karpfen.ide.ui.workbench.settings.WindowBounds
import kotlinx.coroutines.flow.StateFlow

/**
 * The tool windows around the editor area (spec §8), with their [title] (tool-window header and View menu) and default
 * size in dp (width for side windows, height for bottom ones). A new tool window is a new entry here: the persisted
 * form is a map by [name] (no schema change), the toggle command id is `WorkbenchCommandIds.toggleToolWindow(id)` and
 * the View menu iterates the entries.
 */
public enum class ToolWindowId(public val title: String, public val defaultSize: Float) {
    /** Project tree (left). */
    PROJECT("Project", 260f),

    /** Outline of the active document (right). */
    OUTLINE("Outline", 240f),

    /** Problems (bottom). */
    PROBLEMS("Problems", 200f),
    ;

    /** Visible with the default size. */
    public val defaultLayout: ToolWindowLayout get() = ToolWindowLayout(visible = true, size = defaultSize)
}

/**
 * The live window layout (spec §8): tool-window visibility and sizes, the theme and the main window's bounds.
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
 * The window layout as live UI state (spec §8): a Compose-free sub-state of the workbench. Its intents change only
 * this state, at any rate (e.g. every frame of a split-pane drag); persistence is the mediator's job: it writes
 * [state] into the settings through the `SettingsRepository` debounced and once more in `requestExit`, and restores
 * it after loading the settings ([restore]).
 */
public class LayoutState {

    /** The current layout. */
    public val state: StateFlow<LayoutUiState> get() = TODO("WP-WORKBENCH: layout state")

    /** Shows or hides a tool window. */
    public fun toggleToolWindow(id: ToolWindowId): Unit = TODO("WP-WORKBENCH: toggle tool window")

    /**
     * Sets a tool window's size in dp (width for PROJECT/OUTLINE, height for PROBLEMS), clamped to a minimum. The
     * workbench passes the value of the size-based `SplitPane`'s `onFixedSizeChange` unchanged (`Dp.value`), so the
     * composable converts nothing.
     */
    public fun resizeToolWindow(id: ToolWindowId, size: Float): Unit = TODO("WP-WORKBENCH: resize tool window")

    /** Switches the theme. */
    public fun setTheme(mode: ThemeMode): Unit = TODO("WP-WORKBENCH: theme")

    /** Records the main window's bounds. */
    public fun updateWindowBounds(bounds: WindowBounds): Unit = TODO("WP-WORKBENCH: window bounds")

    /** Replaces the layout by the one stored in [settings] (`LayoutUiState.of`). Mediator only. */
    internal fun restore(settings: IdeSettings): Unit = TODO("WP-WORKBENCH: restore layout")
}
