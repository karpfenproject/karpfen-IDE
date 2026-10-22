package io.karpfen.ide.app

import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.action.EditorActionTitles
import io.karpfen.ide.ui.foundation.command.MenuEntry
import io.karpfen.ide.ui.foundation.command.MenuSpec
import io.karpfen.ide.ui.workbench.command.WorkbenchCommandIds
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The structure of the window menus: File, Edit, View and Navigate with their items and separators. */
class IdeMenusTest {

    private fun MenuSpec.itemIds(): List<String> = entries.flatMap { it.itemIds() }

    private fun MenuEntry.itemIds(): List<String> = when (this) {
        is MenuEntry.Item -> listOf(commandId)
        MenuEntry.Separator -> emptyList()
        is MenuEntry.Submenu -> entries.flatMap { it.itemIds() }
    }

    private fun menu(title: String, recent: Int = 0): MenuSpec = IdeMenus.specs(recent).single { it.title == title }

    @Test
    fun `the menu bar has File, Edit, View and Navigate`() {
        assertEquals(listOf("File", "Edit", "View", "Navigate"), IdeMenus.specs(0).map { it.title })
    }

    @Test
    fun `File opens folders, recent folders, saves and closes tabs`() {
        val file = menu(IdeMenus.FILE, recent = 3)

        assertEquals(
            listOf(
                WorkbenchCommandIds.OPEN_FOLDER,
                "workbench.openRecent.0",
                "workbench.openRecent.1",
                "workbench.openRecent.2",
                WorkbenchCommandIds.SAVE,
                WorkbenchCommandIds.SAVE_ALL,
                WorkbenchCommandIds.CLOSE_TAB,
            ),
            file.itemIds(),
        )
        val recent = file.entries.filterIsInstance<MenuEntry.Submenu>().single()
        assertEquals(IdeMenus.OPEN_RECENT, recent.title)
        assertEquals(3, recent.entries.size)
    }

    @Test
    fun `without recent folders the Open Recent submenu is empty`() {
        val recent = menu(IdeMenus.FILE, recent = 0).entries.filterIsInstance<MenuEntry.Submenu>().single()

        assertEquals(emptyList(), recent.entries)
    }

    @Test
    fun `Edit has the editing commands including Find Next and Find Previous`() {
        assertEquals(
            listOf(
                EditorActionIds.UNDO, EditorActionIds.REDO,
                EditorActionIds.CUT, EditorActionIds.COPY, EditorActionIds.PASTE, EditorActionIds.SELECT_ALL,
                EditorActionIds.FIND, EditorActionIds.REPLACE, EditorActionIds.FIND_NEXT, EditorActionIds.FIND_PREVIOUS,
                EditorActionIds.TOGGLE_COMMENT, EditorActionIds.DUPLICATE,
            ),
            menu(IdeMenus.EDIT).itemIds(),
        )
        assertEquals("Duplicate Line", EditorActionTitles.of(EditorActionIds.DUPLICATE), "the Edit menu names it Duplicate Line")
    }

    @Test
    fun `View has one toggle per tool window in enum order and the two themes`() {
        assertEquals(
            ToolWindowId.entries.map { "workbench.toggle.${it.name}" } + listOf(WorkbenchCommandIds.THEME_LIGHT, WorkbenchCommandIds.THEME_DARK),
            menu(IdeMenus.VIEW).itemIds(),
        )
        assertEquals(ToolWindowId.entries.map(WorkbenchCommandIds::toggleToolWindow), menu(IdeMenus.VIEW).itemIds().take(ToolWindowId.entries.size))
    }

    @Test
    fun `Navigate goes to a line or a definition`() {
        assertEquals(listOf(EditorActionIds.GO_TO_LINE, EditorActionIds.GO_TO_DEFINITION), menu(IdeMenus.NAVIGATE).itemIds())
    }

    @Test
    fun `every command appears once and every editor command has a title`() {
        val ids = IdeMenus.specs(recentFolderCount = 10).flatMap { it.itemIds() }

        assertEquals(ids.distinct(), ids)
        ids.filter { it.startsWith("editor.") }.forEach { id -> assertTrue(id in EditorActionTitles.titles, id) }
    }

    @Test
    fun `groups are separated without leading or trailing separators`() {
        IdeMenus.specs(2).forEach { spec ->
            assertTrue(spec.entries.first() != MenuEntry.Separator && spec.entries.last() != MenuEntry.Separator, spec.title)
            assertTrue(spec.entries.zipWithNext().none { (a, b) -> a == MenuEntry.Separator && b == MenuEntry.Separator }, spec.title)
        }
    }

    @Test
    fun `a negative recent-folder count is a programming error`() {
        assertFailsWith<IllegalArgumentException> { IdeMenus.specs(-1) }
    }
}
