package io.karpfen.ide.ui.workbench

import io.karpfen.ide.ui.workbench.layout.ToolWindowId

/** Test tags of the workbench's composables, for headless UI tests (WP-WORKBENCH's and INTEGRATION's). */
public object WorkbenchTestTags {
    /** The empty state shown without a folder. */
    public const val WELCOME: String = "Workbench.welcome"

    /** The empty state's "Open Folder…" button. */
    public const val OPEN_FOLDER: String = "Workbench.openFolder"

    /** The empty state's entry of the recent [folder]. */
    public fun recentFolder(folder: String): String = "Workbench.recent:$folder"

    /** The tool window [id] (its content). */
    public fun toolWindow(id: ToolWindowId): String = "Workbench.toolWindow:${id.name}"

    /** The editor area (tab strip, banner and editor). */
    public const val EDITOR_AREA: String = "Workbench.editorArea"

    /** The `CodeEditor` of the active tab. */
    public const val EDITOR: String = "Workbench.editor"

    /** The external-change banner of the active tab. */
    public const val CONFLICT_BANNER: String = "Workbench.conflictBanner"

    /** A button of the external-change banner, by its label ("Reload", "Close", "Keep mine"). */
    public fun conflictButton(label: String): String = "Workbench.conflictBanner.button:$label"

    /** The Problems view's severity filter toggle [name] ("errors", "warnings", "infos"). */
    public fun problemsFilter(name: String): String = "Workbench.problems.filter:$name"

    /** The modal dialog. */
    public const val DIALOG: String = "Workbench.dialog"

    /** The dialog's text field. */
    public const val DIALOG_INPUT: String = "Workbench.dialog.input"

    /** The dialog's button [label]. */
    public fun dialogButton(label: String): String = "Workbench.dialog.button:$label"
}
