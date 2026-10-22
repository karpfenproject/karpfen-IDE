package io.karpfen.ide.app.menu

import io.karpfen.ide.ui.foundation.command.Command
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.foundation.command.MenuEntry
import io.karpfen.ide.ui.foundation.command.MenuSpec
import io.karpfen.ide.ui.foundation.keys.KeyShortcut
import kotlinx.coroutines.flow.StateFlow

/** A top-level menu of the window menu bar, resolved against the registered commands; never empty. */
internal data class ResolvedMenu(val title: String, val entries: List<ResolvedMenuEntry>)

/** An entry of a [ResolvedMenu], ready to render. */
internal sealed interface ResolvedMenuEntry {

    /**
     * The registered [command]. [accelerator] is the shortcut the menu binds for it, or null when the item shows no
     * shortcut: the command has none, the registry answers another command for that stroke, or the command already has
     * an item earlier in the menus. So every stroke is bound to at most one menu item, and to the same command the
     * registry would run for it: one key press runs one command once.
     */
    class Item internal constructor(
        val command: Command,
        val accelerator: KeyShortcut?,
        private val registry: CommandRegistry,
    ) : ResolvedMenuEntry {
        /** The menu label (the command's title). */
        val title: String get() = command.title

        /** Whether the item is enabled (the command's live enablement). */
        val isEnabled: StateFlow<Boolean> get() = command.isEnabled

        /**
         * Runs the command through the registry: the one dispatch path of a click and of the accelerator. Runs only
         * if the command is still registered and enabled at that moment; true if it ran.
         */
        fun invoke(): Boolean = registry.execute(command.id)

        override fun toString(): String = "Item(${command.id}, accelerator=$accelerator)"
    }

    /** A separator line between two groups of items. */
    data object Separator : ResolvedMenuEntry

    /** A nested menu; never empty. */
    data class Submenu(val title: String, val entries: List<ResolvedMenuEntry>) : ResolvedMenuEntry
}

/**
 * Resolves [specs] against the commands of [registry] (Compose-free): items of unregistered command ids are hidden,
 * then submenus and menus left without items are hidden, and separators that would lead, trail or repeat are dropped.
 * Titles, shortcuts and enablement come from the commands, their one source; the shortcut rule is
 * [ResolvedMenuEntry.Item.accelerator]. `IdeMenuBar` only renders the result.
 */
internal fun resolveMenus(specs: List<MenuSpec>, registry: CommandRegistry): List<ResolvedMenu> =
    MenuResolver(registry).resolve(specs)

/** One resolution; remembers the commands placed so far (so a repeated command gets no second accelerator). */
private class MenuResolver(private val registry: CommandRegistry) {
    private val placed = mutableSetOf<String>()

    /** The menus of [specs] that keep at least one item. */
    fun resolve(specs: List<MenuSpec>): List<ResolvedMenu> =
        specs.mapNotNull { spec -> entriesOf(spec.entries)?.let { ResolvedMenu(spec.title, it) } }

    /** The tidy entries, or null if no item is left. */
    private fun entriesOf(entries: List<MenuEntry>): List<ResolvedMenuEntry>? =
        tidy(entries.mapNotNull(::resolve)).takeIf { resolved -> resolved.isNotEmpty() }

    /** The resolved [entry], or null if nothing of it is left. */
    private fun resolve(entry: MenuEntry): ResolvedMenuEntry? = when (entry) {
        is MenuEntry.Item -> item(entry.commandId)
        MenuEntry.Separator -> ResolvedMenuEntry.Separator
        is MenuEntry.Submenu -> entriesOf(entry.entries)?.let { ResolvedMenuEntry.Submenu(entry.title, it) }
    }

    /** The item of [commandId] with its accelerator (see [ResolvedMenuEntry.Item]), or null if it is not registered. */
    private fun item(commandId: String): ResolvedMenuEntry.Item? {
        val command = registry.find(commandId) ?: return null
        val firstPlacement = placed.add(commandId)
        val accelerator = command.shortcut?.takeIf { shortcut ->
            firstPlacement && registry.commandFor(shortcut.strokeFor(registry.platform)) === command
        }
        return ResolvedMenuEntry.Item(command, accelerator, registry)
    }

    /** Drops separators at the start, at the end and after another separator. */
    private fun tidy(entries: List<ResolvedMenuEntry>): List<ResolvedMenuEntry> {
        val result = mutableListOf<ResolvedMenuEntry>()
        for (entry in entries) {
            val redundant = entry == ResolvedMenuEntry.Separator &&
                (result.isEmpty() || result.last() == ResolvedMenuEntry.Separator)
            if (!redundant) result += entry
        }
        if (result.lastOrNull() == ResolvedMenuEntry.Separator) result.removeAt(result.lastIndex)
        return result
    }
}
