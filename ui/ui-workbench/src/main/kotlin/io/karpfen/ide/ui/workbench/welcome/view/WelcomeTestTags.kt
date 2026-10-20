package io.karpfen.ide.ui.workbench.welcome.view

/** Test tags of the empty state ([WelcomeView]). */
public object WelcomeTestTags {
    /** The empty state shown without a folder. */
    public const val VIEW: String = "Workbench.welcome"

    /** The "Open Folder…" button. */
    public const val OPEN_FOLDER: String = "Workbench.welcome.openFolder"

    /** The entry of the recent [folder]. */
    public fun recentFolder(folder: String): String = "Workbench.welcome.recent:$folder"
}
