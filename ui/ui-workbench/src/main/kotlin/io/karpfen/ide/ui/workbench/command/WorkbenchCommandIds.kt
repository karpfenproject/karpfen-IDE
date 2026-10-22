package io.karpfen.ide.ui.workbench.command

import io.karpfen.ide.ui.workbench.layout.ToolWindowId

/**
 * Ids of the commands the workbench registers in the `CommandRegistry` (`WorkbenchState.registerCommands`), referenced
 * by the application's menus. Editor commands (Undo … Go to Definition) are registered under the editor's action ids
 * (`EditorActionIds`) and forwarded to the active editor.
 */
public object WorkbenchCommandIds {
    /** File menu: Open Folder…, which asks for a folder and opens it. */
    public const val OPEN_FOLDER: String = "workbench.openFolder"

    /** Prefix of the File menu's Open Recent commands; the n-th recent folder (0-based) is `OPEN_RECENT_PREFIX + n`. */
    public const val OPEN_RECENT_PREFIX: String = "workbench.openRecent."

    /** File menu: Save of the active tab. */
    public const val SAVE: String = "workbench.save"

    /** File menu: Save All open documents. */
    public const val SAVE_ALL: String = "workbench.saveAll"

    /** File menu: Close Tab of the active tab. */
    public const val CLOSE_TAB: String = "workbench.closeTab"

    /** Prefix of the View menu's tool-window toggles; see [toggleToolWindow]. */
    public const val TOGGLE_TOOL_WINDOW_PREFIX: String = "workbench.toggle."

    /** View menu: the light theme. */
    public const val THEME_LIGHT: String = "workbench.themeLight"

    /** View menu: the dark theme. */
    public const val THEME_DARK: String = "workbench.themeDark"

    /**
     * The id of the command that shows or hides tool window [id] (`workbench.toggle.PROJECT`, …), derived from the enum,
     * so a new `ToolWindowId` needs no new constant (the menus iterate `ToolWindowId.entries`).
     */
    public fun toggleToolWindow(id: ToolWindowId): String = TOGGLE_TOOL_WINDOW_PREFIX + id.name
}
