package io.karpfen.ide.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope
import io.karpfen.ide.app.menu.ResolvedMenuEntry
import io.karpfen.ide.app.menu.resolveMenus
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.foundation.command.MenuEntry
import io.karpfen.ide.ui.foundation.command.MenuSpec
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.compose.toMenuShortcut
import io.karpfen.ide.ui.workbench.command.WorkbenchCommandIds
import io.karpfen.ide.ui.workbench.layout.ToolWindowId

/**
 * The window menus: File (Open Folder…, Open Recent, Save, Save All, Close Tab), Edit (Undo, Redo, Cut, Copy, Paste,
 * Select All, Find, Replace, Find Next, Find Previous, Toggle Comment, Duplicate Line, Complete Code), View (one toggle
 * per `ToolWindowId.entries` with its `title` and `WorkbenchCommandIds.toggleToolWindow(id)`, Light/Dark theme),
 * Navigate (Go to Line, Go to Definition); items reference `WorkbenchCommandIds` and `EditorActionIds`.
 *
 * Menus hold command ids only: titles, shortcuts and enablement come from the registered commands (the workbench
 * registers the tool-window toggles with `ToolWindowId.title` and the editor commands with `EditorActionTitles`).
 * Edit lists Complete Code although the editor handles its keys itself, so that the window shows the completion
 * shortcut, Ctrl+M, which nobody would guess.
 */
public object IdeMenus {
    /** Title of the File menu. */
    public const val FILE: String = "File"

    /** Title of the Edit menu. */
    public const val EDIT: String = "Edit"

    /** Title of the View menu. */
    public const val VIEW: String = "View"

    /** Title of the Navigate menu. */
    public const val NAVIGATE: String = "Navigate"

    /** Title of the File menu's submenu of recent folders. */
    public const val OPEN_RECENT: String = "Open Recent"

    /**
     * The menu structure; [recentFolderCount] "Open Recent" items reference `OPEN_RECENT_PREFIX + index`.
     * @throws IllegalArgumentException if [recentFolderCount] is negative.
     */
    public fun specs(recentFolderCount: Int): List<MenuSpec> {
        require(recentFolderCount >= 0) { "recentFolderCount must not be negative, was $recentFolderCount" }
        return listOf(file(recentFolderCount), edit(), view(), navigate())
    }

    private fun file(recentFolderCount: Int) = MenuSpec(
        FILE,
        listOf(
            item(WorkbenchCommandIds.OPEN_FOLDER),
            MenuEntry.Submenu(OPEN_RECENT, List(recentFolderCount) { index -> item(WorkbenchCommandIds.OPEN_RECENT_PREFIX + index) }),
            MenuEntry.Separator,
            item(WorkbenchCommandIds.SAVE),
            item(WorkbenchCommandIds.SAVE_ALL),
            MenuEntry.Separator,
            item(WorkbenchCommandIds.CLOSE_TAB),
        ),
    )

    private fun edit() = MenuSpec(
        EDIT,
        listOf(
            item(EditorActionIds.UNDO),
            item(EditorActionIds.REDO),
            MenuEntry.Separator,
            item(EditorActionIds.CUT),
            item(EditorActionIds.COPY),
            item(EditorActionIds.PASTE),
            item(EditorActionIds.SELECT_ALL),
            MenuEntry.Separator,
            item(EditorActionIds.FIND),
            item(EditorActionIds.REPLACE),
            item(EditorActionIds.FIND_NEXT),
            item(EditorActionIds.FIND_PREVIOUS),
            MenuEntry.Separator,
            item(EditorActionIds.TOGGLE_COMMENT),
            item(EditorActionIds.DUPLICATE),
            MenuEntry.Separator,
            item(EditorActionIds.COMPLETION),
        ),
    )

    private fun view() = MenuSpec(
        VIEW,
        ToolWindowId.entries.map { id -> item(WorkbenchCommandIds.toggleToolWindow(id)) } +
            listOf(
                MenuEntry.Separator,
                item(WorkbenchCommandIds.THEME_LIGHT),
                item(WorkbenchCommandIds.THEME_DARK),
            ),
    )

    private fun navigate() = MenuSpec(
        NAVIGATE,
        listOf(item(EditorActionIds.GO_TO_LINE), item(EditorActionIds.GO_TO_DEFINITION)),
    )

    private fun item(commandId: String) = MenuEntry.Item(commandId)
}

/**
 * The native menu bar built from [menus] and the commands in [registry] (titles, shortcuts, enablement); items of
 * unregistered command ids are hidden. Accelerators are bound with `KeyShortcut.toMenuShortcut`. Key routing: the
 * focused component handles and consumes its keys first (Swing key bindings skip consumed events; on macOS the screen
 * menu bar delivers key equivalents as Java key events first), so an accelerator only fires for strokes nobody consumed
 * and one key press runs a command once. The resolution of [menus] against [registry] is a Compose-free internal
 * function (testable headless); this composable only renders its result.
 *
 * Clicks and accelerators run the command through `CommandRegistry.execute`, the window installs no key handler of
 * its own, and each stroke is bound to at most one item, so this menu bar is the only window-level dispatch of a
 * command shortcut.
 */
@Composable
public fun FrameWindowScope.IdeMenuBar(registry: CommandRegistry, menus: List<MenuSpec>) {
    val commands by registry.commands.collectAsState()
    val resolved = remember(menus, commands) { resolveMenus(menus, registry) }
    MenuBar {
        resolved.forEach { menu ->
            key(menu.title) {
                Menu(menu.title) { MenuEntries(menu.entries, registry.platform) }
            }
        }
    }
}

/** Renders resolved entries; contains no decisions of its own. */
@Composable
private fun MenuScope.MenuEntries(entries: List<ResolvedMenuEntry>, platform: HostPlatform) {
    entries.forEachIndexed { index, entry ->
        key(index, entry) {
            when (entry) {
                is ResolvedMenuEntry.Item -> {
                    val enabled by entry.isEnabled.collectAsState()
                    Item(
                        text = entry.title,
                        enabled = enabled,
                        shortcut = entry.accelerator?.toMenuShortcut(platform),
                        onClick = { entry.invoke() },
                    )
                }
                ResolvedMenuEntry.Separator -> Separator()
                is ResolvedMenuEntry.Submenu -> Menu(entry.title) { MenuEntries(entry.entries, platform) }
            }
        }
    }
}
