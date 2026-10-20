package io.karpfen.ide.ui.foundation.command

import io.karpfen.ide.ui.foundation.keys.KeyShortcut
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A user-invocable command (Command pattern, spec §6): menus, shortcuts and context menus reference it by [id].
 * Editor commands use the editor's action ids, so menu and keyboard reach the same `EditorAction`.
 *
 * A command object, not a value: it holds behaviour ([action]) and live state ([isEnabled]), so it has identity
 * equality (no `copy`, no structural `equals`); the registry identifies commands by [id].
 *
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
 * Data from which the application builds its window menu bar (spec §6, §9): a top-level menu with its entries.
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
