package io.karpfen.ide.ui.workbench.tabs.view

/** Test tags of the editor area ([EditorArea]); the tabs themselves carry `TabStripTestTags`. */
public object EditorAreaTestTags {
    /** The editor area (tab strip, banner and editor). */
    public const val AREA: String = "Workbench.editorArea"

    /** The `CodeEditor` of the active tab. */
    public const val EDITOR: String = "Workbench.editor"

    /** The external-change banner of the active tab. */
    public const val CONFLICT_BANNER: String = "Workbench.conflictBanner"

    /** A button of the external-change banner, by its label ("Reload", "Close", "Keep mine"). */
    public fun conflictButton(label: String): String = "Workbench.conflictBanner.button:$label"
}
