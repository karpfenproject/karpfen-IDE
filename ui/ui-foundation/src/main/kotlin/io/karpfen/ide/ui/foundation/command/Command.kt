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
package io.karpfen.ide.ui.foundation.command

import io.karpfen.ide.ui.foundation.keys.KeyShortcut
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A user-invocable command (Command pattern): menus, shortcuts and context menus reference it by [id]. Editor commands
 * use the editor's action ids, so menu and keyboard reach the same `EditorAction`.
 *
 * A command object, not a value: it holds behaviour ([action]) and live state ([isEnabled]), so it has identity
 * equality (no `copy`, no structural `equals`); the registry identifies commands by [id].
 *
 * @property id the identifier menus and the registry know the command by.
 * @property title menu label.
 * @property shortcut default shortcut, or null.
 * @property isEnabled whether the command can run now (menus grey it out when false).
 * @property action runs the command on the UI thread.
 */
public class Command(
    public val id: String,
    public val title: String,
    public val shortcut: KeyShortcut? = null,
    public val isEnabled: StateFlow<Boolean> = ALWAYS_ENABLED,
    public val action: () -> Unit,
) {
    override fun toString(): String = "Command($id)"

    public companion object {
        /** A flow that is always true. */
        public val ALWAYS_ENABLED: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    }
}

/**
 * Data from which the application builds its window menu bar: a top-level menu with its entries.
 *
 * @property title the menu's label in the menu bar.
 * @property entries the menu's items, separators and submenus in display order.
 */
public data class MenuSpec(public val title: String, public val entries: List<MenuEntry>)

/** An entry of a [MenuSpec]. */
public sealed interface MenuEntry {
    /** The registered command [commandId]; hidden if no such command is registered. */
    public data class Item(public val commandId: String) : MenuEntry

    /** A separator line. */
    public data object Separator : MenuEntry

    /** A nested menu. */
    public data class Submenu(public val title: String, public val entries: List<MenuEntry>) : MenuEntry
}
