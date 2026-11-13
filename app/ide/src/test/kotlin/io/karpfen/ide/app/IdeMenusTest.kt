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

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.action.EditorActionTitles
import io.karpfen.ide.ui.foundation.command.MenuEntry
import io.karpfen.ide.ui.foundation.command.MenuSpec
import io.karpfen.ide.ui.workbench.command.RuntimeCommandIds
import io.karpfen.ide.ui.workbench.command.WorkbenchCommandIds
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The structure of the window menus: File, Edit, View, Navigate and Run with their items and separators. */
class IdeMenusTest {

    private fun MenuSpec.itemIds(): List<String> = entries.flatMap { it.itemIds() }

    private fun MenuEntry.itemIds(): List<String> = when (this) {
        is MenuEntry.Item -> listOf(commandId)
        MenuEntry.Separator -> emptyList()
        is MenuEntry.Submenu -> entries.flatMap { it.itemIds() }
    }

    private fun menu(title: String, recent: Int = 0): MenuSpec = IdeMenus.specs(recent).single { it.title == title }

    @Test
    fun `the menu bar has File, Edit, View, Navigate and Run`() {
        assertEquals(listOf("File", "Edit", "View", "Navigate", "Run"), IdeMenus.specs(0).map { it.title })
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
    fun `Edit has the editing commands including Find Next, Find Previous and Complete Code`() {
        assertEquals(
            listOf(
                EditorActionIds.UNDO, EditorActionIds.REDO,
                EditorActionIds.CUT, EditorActionIds.COPY, EditorActionIds.PASTE, EditorActionIds.SELECT_ALL,
                EditorActionIds.FIND, EditorActionIds.REPLACE, EditorActionIds.FIND_NEXT, EditorActionIds.FIND_PREVIOUS,
                EditorActionIds.TOGGLE_COMMENT, EditorActionIds.DUPLICATE,
                EditorActionIds.COMPLETION,
            ),
            menu(IdeMenus.EDIT).itemIds(),
        )
        assertEquals("Duplicate Line", EditorActionTitles.of(EditorActionIds.DUPLICATE), "the Edit menu names it Duplicate Line")
        assertEquals("Complete Code", EditorActionTitles.of(EditorActionIds.COMPLETION), "and completion Complete Code")
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
    fun `View opens the Language Tools Output, which is hidden until then`() {
        val view = menu(IdeMenus.VIEW).itemIds()

        assertTrue(WorkbenchCommandIds.toggleToolWindow(ToolWindowId.OUTPUT) in view, "$view")
        assertEquals("Language Tools Output", ToolWindowId.OUTPUT.title, "the title of its menu item and its tool window")
        assertFalse(ToolWindowId.OUTPUT.visibleByDefault)
    }

    @Test
    fun `Navigate goes to a line or a definition`() {
        assertEquals(listOf(EditorActionIds.GO_TO_LINE, EditorActionIds.GO_TO_DEFINITION), menu(IdeMenus.NAVIGATE).itemIds())
    }

    @Test
    fun `Run runs and stops, debugs, starts and stops the runtime, configures a bridge and switches language features`() {
        assertEquals(
            listOf(
                RuntimeCommandIds.RUN,
                RuntimeCommandIds.STOP,
                RuntimeCommandIds.PAUSE,
                RuntimeCommandIds.RESUME,
                RuntimeCommandIds.STEP,
                RuntimeCommandIds.START_RUNTIME,
                RuntimeCommandIds.STOP_RUNTIME,
                RuntimeCommandIds.CONFIGURE_BRIDGE,
            ) + KstatesFeature.entries.map(WorkbenchCommandIds::languageFeature),
            menu(IdeMenus.RUN).itemIds(),
        )
        val features = menu(IdeMenus.RUN).entries.filterIsInstance<MenuEntry.Submenu>().single()
        assertEquals(IdeMenus.LANGUAGE_FEATURES, features.title)
        assertEquals(
            listOf(
                "workbench.languageFeature.history",
                "workbench.languageFeature.tickLimit",
                "workbench.languageFeature.breakpoints",
                "workbench.languageFeature.macros",
                "workbench.languageFeature.parallel",
            ),
            features.itemIds(),
        )
        val view = menu(IdeMenus.VIEW).itemIds()
        assertTrue(WorkbenchCommandIds.toggleToolWindow(ToolWindowId.RUNTIME) in view, "the Runtime toggle is in View: $view")
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
