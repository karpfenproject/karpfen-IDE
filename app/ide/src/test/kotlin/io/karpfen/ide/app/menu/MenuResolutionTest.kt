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
package io.karpfen.ide.app.menu

import io.karpfen.ide.app.IdeMenus
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.action.EditorActionTitles
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.foundation.command.Command
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.foundation.command.MenuEntry
import io.karpfen.ide.ui.foundation.command.MenuSpec
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import io.karpfen.ide.ui.foundation.keys.KeyShortcut
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import io.karpfen.ide.ui.workbench.command.WorkbenchCommandIds
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Resolution of the menu structure against the registered commands, and the one-dispatch rule: every stroke is bound to
 * at most one menu item, so one key press runs one command once.
 */
class MenuResolutionTest {

    private val registry = CommandRegistry(HostPlatform.MAC_OS)
    private val runs = mutableMapOf<String, Int>()

    private fun command(id: String, shortcut: KeyShortcut? = null, enabled: Boolean = true) =
        Command(id, "Title of $id", shortcut, MutableStateFlow(enabled)) { runs[id] = (runs[id] ?: 0) + 1 }
            .also(registry::register)

    private fun items(entries: List<ResolvedMenuEntry>): List<ResolvedMenuEntry.Item> = entries.flatMap { entry ->
        when (entry) {
            is ResolvedMenuEntry.Item -> listOf(entry)
            ResolvedMenuEntry.Separator -> emptyList()
            is ResolvedMenuEntry.Submenu -> items(entry.entries)
        }
    }

    private fun shape(entries: List<ResolvedMenuEntry>): List<String> = entries.map { entry ->
        when (entry) {
            is ResolvedMenuEntry.Item -> entry.command.id
            ResolvedMenuEntry.Separator -> "---"
            is ResolvedMenuEntry.Submenu -> "${entry.title}${shape(entry.entries)}"
        }
    }

    @Test
    fun `items take title, shortcut and enablement from their commands`() {
        val save = command("save", KeyShortcut(KeyCode.S, primary = true), enabled = false)

        val item = resolveMenus(listOf(MenuSpec("File", listOf(MenuEntry.Item("save")))), registry).single().entries.single()

        assertIs<ResolvedMenuEntry.Item>(item)
        assertSame(save, item.command)
        assertEquals("Title of save", item.title)
        assertEquals(KeyShortcut(KeyCode.S, primary = true), item.accelerator)
        assertFalse(item.isEnabled.value)
    }

    @Test
    fun `unregistered items are hidden and separators never lead, trail or repeat`() {
        command("a")
        command("b")
        val spec = MenuSpec(
            "Edit",
            listOf(
                MenuEntry.Separator, MenuEntry.Item("missing"), MenuEntry.Separator, MenuEntry.Item("a"),
                MenuEntry.Separator, MenuEntry.Item("missing2"), MenuEntry.Separator, MenuEntry.Item("b"),
                MenuEntry.Separator, MenuEntry.Item("missing3"),
            ),
        )

        assertEquals(listOf("a", "---", "b"), shape(resolveMenus(listOf(spec), registry).single().entries))
    }

    @Test
    fun `submenus and menus without registered items are hidden`() {
        command("a")
        val specs = listOf(
            MenuSpec("File", listOf(MenuEntry.Item("a"), MenuEntry.Submenu("Open Recent", emptyList()), MenuEntry.Submenu("Gone", listOf(MenuEntry.Item("x"))))),
            MenuSpec("Empty", listOf(MenuEntry.Item("y"), MenuEntry.Separator)),
            MenuSpec("Nested", listOf(MenuEntry.Submenu("Outer", listOf(MenuEntry.Submenu("Inner", listOf(MenuEntry.Item("a"))))))),
        )

        val resolved = resolveMenus(specs, registry)

        assertEquals(listOf("File", "Nested"), resolved.map { it.title })
        assertEquals(listOf("a"), shape(resolved[0].entries))
        assertEquals(listOf("Outer[Inner[a]]"), shape(resolved[1].entries))
    }

    @Test
    fun `invoking an item runs its command once through the registry, and only while it is enabled`() {
        val enabled = MutableStateFlow(true)
        registry.register(Command("run", "Run", isEnabled = enabled) { runs["run"] = (runs["run"] ?: 0) + 1 })
        val item = items(resolveMenus(listOf(MenuSpec("M", listOf(MenuEntry.Item("run")))), registry).single().entries).single()

        assertTrue(item.invoke())
        enabled.value = false
        assertFalse(item.invoke())

        assertEquals(1, runs["run"])
    }

    @Test
    fun `a stroke shared by two commands is bound only to the item of the command the registry answers`() {
        val shortcut = KeyShortcut(KeyCode.D, primary = true)
        command("older", shortcut)
        val newer = command("newer", shortcut)

        val items = items(resolveMenus(listOf(MenuSpec("M", listOf(MenuEntry.Item("older"), MenuEntry.Item("newer")))), registry).single().entries)

        assertSame(newer, registry.commandFor(shortcut.strokeFor(HostPlatform.MAC_OS)))
        assertEquals(mapOf("older" to null, "newer" to shortcut), items.associate { it.command.id to it.accelerator })
    }

    @Test
    fun `a command listed twice gets its accelerator only on its first item`() {
        val shortcut = KeyShortcut(KeyCode.S, primary = true)
        command("save", shortcut)
        val specs = listOf(MenuSpec("A", listOf(MenuEntry.Item("save"))), MenuSpec("B", listOf(MenuEntry.Item("save"))))

        val items = resolveMenus(specs, registry).flatMap { items(it.entries) }

        assertEquals(listOf(shortcut, null), items.map { it.accelerator })
    }

    @Test
    fun `with every command of the menus registered, each stroke is bound once and runs exactly one command once`() {
        val keymap = defaultEditorKeymap(HostPlatform.MAC_OS)
        val workbenchIds = listOf(
            WorkbenchCommandIds.OPEN_FOLDER, WorkbenchCommandIds.OPEN_RECENT_PREFIX + 0, WorkbenchCommandIds.OPEN_RECENT_PREFIX + 1,
            WorkbenchCommandIds.SAVE, WorkbenchCommandIds.SAVE_ALL, WorkbenchCommandIds.CLOSE_TAB,
            WorkbenchCommandIds.THEME_LIGHT, WorkbenchCommandIds.THEME_DARK,
        ) + ToolWindowId.entries.map(WorkbenchCommandIds::toggleToolWindow)
        val workbenchShortcuts = mapOf(
            WorkbenchCommandIds.SAVE to KeyShortcut(KeyCode.S, primary = true),
            WorkbenchCommandIds.SAVE_ALL to KeyShortcut(KeyCode.S, primary = true, alt = true),
            WorkbenchCommandIds.CLOSE_TAB to KeyShortcut(KeyCode.W, primary = true),
            WorkbenchCommandIds.OPEN_FOLDER to KeyShortcut(KeyCode.O, primary = true),
        )
        workbenchIds.forEach { id -> command(id, workbenchShortcuts[id]) }
        EditorActionTitles.titles.keys.forEach { id -> command(id, keymap.shortcutFor(id)) }

        val items = resolveMenus(IdeMenus.specs(recentFolderCount = 2), registry).flatMap { items(it.entries) }
        val bound = items.mapNotNull { item -> item.accelerator?.let { it.strokeFor(HostPlatform.MAC_OS) to item } }

        assertEquals(bound.map { it.first }.distinct(), bound.map { it.first }, "no stroke is bound to two items")
        assertTrue(bound.size >= 10, "the menu binds the common shortcuts: ${bound.map { it.first }}")
        bound.forEach { (stroke, item) ->
            runs.clear()
            assertTrue(item.invoke())
            assertEquals(mapOf(item.command.id to 1), runs, "$stroke runs exactly ${item.command.id}, once")
            assertSame(registry.commandFor(stroke), item.command)
        }
        val undo = KeyStroke(KeyCode.Z, setOf(KeyModifier.META))
        assertEquals(EditorActionIds.UNDO, bound.single { it.first == undo }.second.command.id)
    }

    @Test
    fun `items without a shortcut have no accelerator`() {
        command("plain")

        val item = items(resolveMenus(listOf(MenuSpec("M", listOf(MenuEntry.Item("plain")))), registry).single().entries).single()

        assertNull(item.accelerator)
    }
}
