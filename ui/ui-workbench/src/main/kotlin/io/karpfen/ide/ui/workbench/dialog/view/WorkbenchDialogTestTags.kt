package io.karpfen.ide.ui.workbench.dialog.view

/** Test tags of the modal dialog ([WorkbenchDialogs]). */
public object WorkbenchDialogTestTags {
    /** The dialog. */
    public const val DIALOG: String = "Workbench.dialog"

    /** The dialog's text field (rename, new file). */
    public const val INPUT: String = "Workbench.dialog.input"

    /** The dialog's button [label]. */
    public fun button(label: String): String = "Workbench.dialog.button:$label"
}
