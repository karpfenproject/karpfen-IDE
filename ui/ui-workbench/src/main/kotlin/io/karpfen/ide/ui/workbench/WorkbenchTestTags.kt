package io.karpfen.ide.ui.workbench

import io.karpfen.ide.ui.workbench.layout.ToolWindowId

/**
 * Test tags of the workbench layout, for the headless UI tests and the end-to-end tests. The feature views have tags of
 * their own next to them (`WelcomeTestTags`, `EditorAreaTestTags`, `ProblemsViewTestTags`, `WorkbenchDialogTestTags`),
 * so no view package depends on the root package.
 */
public object WorkbenchTestTags {
    /** The tool window [id] (its frame with header and content). */
    public fun toolWindow(id: ToolWindowId): String = "Workbench.toolWindow:${id.name}"
}
