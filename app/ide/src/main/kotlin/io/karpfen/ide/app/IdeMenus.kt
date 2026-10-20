package io.karpfen.ide.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.foundation.command.MenuSpec

/**
 * The window menus (spec §9): File (Open Folder…, Open Recent, Save, Save All, Close Tab), Edit (Undo, Redo, Cut,
 * Copy, Paste, Select All, Find, Replace, Toggle Comment, Duplicate Line), View (Project, Outline, Problems,
 * Light/Dark theme), Navigate (Go to Line, Go to Definition); items reference `WorkbenchCommandIds` and
 * `EditorActionIds`.
 */
public object IdeMenus {
    /** The menu structure; [recentFolderCount] "Open Recent" items reference `OPEN_RECENT_PREFIX + index`. */
    public fun specs(recentFolderCount: Int): List<MenuSpec> = TODO("WP-APP: menu structure")
}

/** The native menu bar built from [menus] and the commands in [registry] (titles, shortcuts, enablement). */
@Composable
public fun FrameWindowScope.IdeMenuBar(registry: CommandRegistry, menus: List<MenuSpec>): Unit =
    TODO("WP-APP: menu bar from the command registry")
