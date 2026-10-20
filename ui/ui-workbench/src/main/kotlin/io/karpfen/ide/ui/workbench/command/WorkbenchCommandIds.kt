package io.karpfen.ide.ui.workbench.command

import io.karpfen.ide.ui.workbench.layout.ToolWindowId

/**
 * Ids of the commands the workbench registers in the `CommandRegistry` (`WorkbenchState.registerCommands`), referenced
 * by the application's menus (spec §9). Editor commands (Undo … Go to Definition) are registered under the editor's
 * action ids (`EditorActionIds`) and forwarded to the active editor.
 */
public object WorkbenchCommandIds {
    // File
    public const val OPEN_FOLDER: String = "workbench.openFolder"

    /** Prefix of the "Open Recent" commands; the n-th recent folder (0-based) is `OPEN_RECENT_PREFIX + n`. */
    public const val OPEN_RECENT_PREFIX: String = "workbench.openRecent."
    public const val SAVE: String = "workbench.save"
    public const val SAVE_ALL: String = "workbench.saveAll"
    public const val CLOSE_TAB: String = "workbench.closeTab"

    // View
    /** Prefix of the tool-window toggles; see [toggleToolWindow]. */
    public const val TOGGLE_TOOL_WINDOW_PREFIX: String = "workbench.toggle."
    public const val THEME_LIGHT: String = "workbench.themeLight"
    public const val THEME_DARK: String = "workbench.themeDark"

    /**
     * The id of the command that shows or hides tool window [id] (`workbench.toggle.PROJECT`, …), derived from the enum,
     * so a new `ToolWindowId` needs no new constant (the menus iterate `ToolWindowId.entries`).
     */
    public fun toggleToolWindow(id: ToolWindowId): String = TOGGLE_TOOL_WINDOW_PREFIX + id.name
}
