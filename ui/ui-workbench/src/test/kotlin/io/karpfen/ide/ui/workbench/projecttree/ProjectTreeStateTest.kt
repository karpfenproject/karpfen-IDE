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
package io.karpfen.ide.ui.workbench.projecttree

import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ProjectTreeState] and [ProjectTreeMenu]. */
class ProjectTreeStateTest {

    private val root: Path = Path.of("/ws").toAbsolutePath()
    private val sub = root.resolve("sub")
    private val deep = sub.resolve("deep")
    private val file = deep.resolve("a.kstates")

    private fun tree(withDeep: Boolean = true): WorkspaceTree {
        val deepNode = WorkspaceNode.Directory(deep, "deep", listOf(WorkspaceNode.File(file, "a.kstates", FileKind.KSTATES)))
        val subNode = WorkspaceNode.Directory(sub, "sub", if (withDeep) listOf(deepNode) else emptyList())
        return WorkspaceTree(WorkspaceNode.Directory(root, "ws", listOf(subNode)))
    }

    @Test
    fun `a new tree starts with the root expanded and nothing selected`() {
        val state = ProjectTreeState()
        assertNull(state.tree.value)
        state.setTree(tree())
        assertEquals(setOf(root), state.expanded.value)
        assertNull(state.selected.value)
        assertTrue(state.isDirectory(sub))
        assertFalse(state.isDirectory(file))
    }

    @Test
    fun `reveal expands the ancestors and selects, toggle and select are plain intents`() {
        val state = ProjectTreeState()
        state.setTree(tree())
        state.reveal(file)
        assertEquals(setOf(root, sub, deep), state.expanded.value)
        assertEquals(file, state.selected.value)

        state.toggle(deep)
        assertEquals(setOf(root, sub), state.expanded.value)
        state.select(sub)
        assertEquals(sub, state.selected.value)
        state.select(null)
        assertNull(state.selected.value)
    }

    @Test
    fun `updates keep what still exists and drop vanished paths`() {
        val state = ProjectTreeState()
        state.setTree(tree())
        state.reveal(file)
        state.setTree(tree(withDeep = false))
        assertEquals(setOf(root, sub), state.expanded.value)
        assertNull(state.selected.value)

        state.setTree(WorkspaceTree.empty(Path.of("/other").toAbsolutePath()))
        assertEquals(setOf(Path.of("/other").toAbsolutePath()), state.expanded.value, "another folder starts afresh")
        state.setTree(null)
        assertEquals(emptySet(), state.expanded.value)
        assertNull(state.tree.value)
    }

    @Test
    fun `the state builds a node's menu from its tree, knowing directories and the root`() {
        val state = ProjectTreeState(ProjectTreeMenu(HostPlatform.MAC_OS) { true })
        state.setTree(tree())

        assertEquals(ProjectTreeCommand.NewFile(deep, FileTemplateKind.KMETA), state.menuFor(file).first().command, "next to a file")
        assertEquals(ProjectTreeCommand.NewFile(sub, FileTemplateKind.KMETA), state.menuFor(sub).first().command, "inside a directory")
        assertTrue(state.menuFor(sub).all { it.isEnabled })
        val forRoot = state.menuFor(root).filter { it.command is ProjectTreeCommand.Rename || it.command is ProjectTreeCommand.Delete }
        assertEquals(listOf(false, false), forRoot.map { it.isEnabled }, "the root can be neither renamed nor deleted")
    }

    @Test
    fun `the context menu offers the templates, rename, delete and the file manager`() {
        val menu = ProjectTreeMenu(HostPlatform.MAC_OS) { true }
        val forFile = menu.entries(file, isDirectory = false, isRoot = false)
        assertEquals(
            FileTemplateKind.entries.map { "New ${it.displayName}" } + listOf("Rename…", "Delete…", "Reveal in Finder"),
            forFile.map { it.label },
        )
        assertEquals(ProjectTreeCommand.NewFile(deep, FileTemplateKind.KMETA), forFile.first().command, "new files go next to a file")
        assertTrue(forFile.all { it.isEnabled })
        val forDirectory = menu.entries(sub, isDirectory = true, isRoot = false)
        assertEquals(ProjectTreeCommand.NewFile(sub, FileTemplateKind.KSTATES), forDirectory[2].command)

        val forRoot = menu.entries(root, isDirectory = true, isRoot = true)
        val renameOrDelete = forRoot.filter { it.command is ProjectTreeCommand.Rename || it.command is ProjectTreeCommand.Delete }
        assertEquals(listOf(false, false), renameOrDelete.map { it.isEnabled })

        assertEquals("Show in Explorer", ProjectTreeMenu(HostPlatform.WINDOWS).fileManagerLabel)
        assertEquals("Show in File Manager", ProjectTreeMenu(HostPlatform.LINUX).fileManagerLabel)
        assertFalse(ProjectTreeMenu(HostPlatform.LINUX).entries(file, false, false).last().isEnabled, "reveal unsupported")
    }
}
