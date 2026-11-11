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
package io.karpfen.ide.ui.workbench.command

import io.karpfen.ide.ui.workbench.layout.ToolWindowId

/**
 * Ids of the commands that `WorkbenchState.registerCommands` registers in the `CommandRegistry`. The application's
 * menus refer to them. The editor commands, from Undo to Go to Definition, are registered under the editor's own
 * action ids from `EditorActionIds` and are forwarded to the active editor.
 */
public object WorkbenchCommandIds {
    /** Open Folder… in the File menu. It asks for a folder and opens it. */
    public const val OPEN_FOLDER: String = "workbench.openFolder"

    /**
     * Prefix of the Open Recent commands of the File menu. The id of the recent folder at index n, counted from 0, is
     * `OPEN_RECENT_PREFIX + n`.
     */
    public const val OPEN_RECENT_PREFIX: String = "workbench.openRecent."

    /** Save in the File menu. It saves the active tab. */
    public const val SAVE: String = "workbench.save"

    /** Save All in the File menu. It saves all open documents. */
    public const val SAVE_ALL: String = "workbench.saveAll"

    /** Close Tab in the File menu. It closes the active tab. */
    public const val CLOSE_TAB: String = "workbench.closeTab"

    /** Prefix of the tool window toggles of the View menu. [toggleToolWindow] builds the full ids. */
    public const val TOGGLE_TOOL_WINDOW_PREFIX: String = "workbench.toggle."

    /** The light theme in the View menu. */
    public const val THEME_LIGHT: String = "workbench.themeLight"

    /** The dark theme in the View menu. */
    public const val THEME_DARK: String = "workbench.themeDark"

    /**
     * The id of the command that shows or hides the tool window [id], for example `workbench.toggle.PROJECT`. The id is
     * derived from the enum constant's name, so a new `ToolWindowId` needs no new constant here. The menus iterate over
     * `ToolWindowId.entries`.
     */
    public fun toggleToolWindow(id: ToolWindowId): String = TOGGLE_TOOL_WINDOW_PREFIX + id.name
}
