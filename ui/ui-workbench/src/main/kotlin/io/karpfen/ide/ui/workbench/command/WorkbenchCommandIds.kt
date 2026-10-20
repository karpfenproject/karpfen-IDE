package io.karpfen.ide.ui.workbench.command

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
    public const val TOGGLE_PROJECT: String = "workbench.toggleProject"
    public const val TOGGLE_OUTLINE: String = "workbench.toggleOutline"
    public const val TOGGLE_PROBLEMS: String = "workbench.toggleProblems"
    public const val THEME_LIGHT: String = "workbench.themeLight"
    public const val THEME_DARK: String = "workbench.themeDark"
}
