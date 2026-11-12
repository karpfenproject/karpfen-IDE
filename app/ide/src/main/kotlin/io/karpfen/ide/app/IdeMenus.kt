/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
import io.karpfen.ide.ui.workbench.command.RuntimeCommandIds
import io.karpfen.ide.ui.workbench.command.WorkbenchCommandIds
import io.karpfen.ide.ui.workbench.layout.ToolWindowId

/**
 * The window menus. File holds Open Folder…, Open Recent, Save, Save All and Close Tab. Edit holds Undo, Redo, Cut,
 * Copy, Paste, Select All, Find, Replace, Find Next, Find Previous, Toggle Comment, Duplicate Line and Complete Code.
 * View holds one toggle per entry of `ToolWindowId.entries`, the Runtime tool window among them, with the entry's
 * `title` and the command `WorkbenchCommandIds.toggleToolWindow(id)`, followed by the light and the dark theme.
 * Navigate holds Go to Line and Go to Definition. Run holds Run, Stop, Start Runtime, Stop Runtime and Configure
 * Bridge. The items refer to `WorkbenchCommandIds`, `EditorActionIds` and `RuntimeCommandIds`.
 *
 * Menus hold command ids only. Titles, shortcuts and enablement come from the registered commands. The workbench
 * registers the tool-window toggles with `ToolWindowId.title` and the editor commands with `EditorActionTitles`. Edit
 * lists Complete Code although the editor handles its keys itself, so that the window shows the completion shortcut,
 * Ctrl+M, which nobody would guess.
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

    /** Title of the Run menu. */
    public const val RUN: String = "Run"

    /** Title of the File menu's submenu of recent folders. */
    public const val OPEN_RECENT: String = "Open Recent"

    /**
     * The menu structure. The Open Recent submenu has [recentFolderCount] items, which refer to the commands
     * `OPEN_RECENT_PREFIX + index`.
     *
     * @throws IllegalArgumentException if [recentFolderCount] is negative.
     */
    public fun specs(recentFolderCount: Int): List<MenuSpec> {
        require(recentFolderCount >= 0) { "recentFolderCount must not be negative, was $recentFolderCount" }
        return listOf(file(recentFolderCount), edit(), view(), navigate(), run())
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

    private fun run() = MenuSpec(
        RUN,
        listOf(
            item(RuntimeCommandIds.RUN),
            item(RuntimeCommandIds.STOP),
            MenuEntry.Separator,
            item(RuntimeCommandIds.START_RUNTIME),
            item(RuntimeCommandIds.STOP_RUNTIME),
            MenuEntry.Separator,
            item(RuntimeCommandIds.CONFIGURE_BRIDGE),
        ),
    )

    private fun item(commandId: String) = MenuEntry.Item(commandId)
}

/**
 * The native menu bar built from [menus] and the commands in [registry], which supply the titles, the shortcuts and
 * the enablement. Items whose command id is not registered are hidden. Accelerators are bound with
 * `KeyShortcut.toMenuShortcut`.
 *
 * The focused component handles and consumes its keys first. Swing key bindings skip consumed events, and on macOS
 * the screen menu bar delivers key equivalents as Java key events first. So an accelerator only fires for a stroke
 * that nobody consumed, and one key press runs a command once. The resolution of [menus] against [registry] is an
 * internal function without Compose, so it can be tested headless. This composable only renders its result.
 *
 * Clicks and accelerators run the command through `CommandRegistry.execute`. The window installs no key handler of
 * its own, and each stroke is bound to at most one item. So this menu bar is the only place in the window that
 * dispatches a command shortcut.
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

/** Renders resolved entries. It makes no decisions of its own. */
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
