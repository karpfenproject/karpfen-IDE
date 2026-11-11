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
package io.karpfen.ide.ui.workbench.tabs

import io.karpfen.ide.core.workspace.document.ExternalChangeKind
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import io.karpfen.ide.ui.foundation.widgets.TabItem
import io.karpfen.ide.ui.workbench.projecttree.view.ProjectTreeNodes
import io.karpfen.ide.ui.workbench.tabs.view.tabItemOf
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Tests the tab strip items of the editor tabs. A tab shows the same icon for its file as the project tree. A tab in
 * conflict is marked on the strip, also when it is not active.
 */
class EditorTabItemsTest {

    private val root: Path = Path.of("/ws").toAbsolutePath()

    private val tab = EditorTab(
        path = root.resolve("robot.kstates"),
        title = "robot.kstates",
        tooltip = "robot.kstates",
        kind = FileKind.KSTATES,
        isDirty = true,
    )

    @Test
    fun `a tab without a conflict shows its title, tooltip, dirty dot and language icon`() {
        assertEquals(TabItem(tab.path.toString(), "robot.kstates", "robot.kstates", isDirty = true, KarpfenIcons.Kstates), tabItemOf(tab))
    }

    @Test
    fun `a tab shows the icon the project tree shows for its file, for every kind an editor opens`() {
        val names = listOf("EVENTS.kmeta", "robot.kenv.json", "robot.kmeta", "robot.kmodel", "robot.kstates")
        val files = names.map { WorkspaceNode.File(root.resolve(it), it, FileKind.of(it)) }
        assertEquals(FileKind.entries.filter { it.language != null }.toSet(), files.map { it.kind }.toSet())

        val tree = ProjectTreeNodes(WorkspaceTree(WorkspaceNode.Directory(root, "ws", files)))
        val treeIcons = tree.roots.single().children.orEmpty().associate { it.label to it.icon }
        val tabIcons = files.associate { file ->
            file.name to tabItemOf(tab.copy(path = file.path, title = file.name, kind = file.kind)).icon
        }

        assertEquals(treeIcons, tabIcons)
        assertSame(KarpfenIcons.Kmeta, tabIcons["EVENTS.kmeta"], "the event metamodel looks like any metamodel")
        assertSame(KarpfenIcons.Json, tabIcons["robot.kenv.json"], "an environment descriptor shows the JSON icon")
    }

    @Test
    fun `a tab in conflict shows the warning icon and the banner's message in its tooltip`() {
        val modified = tabItemOf(tab.copy(conflict = ExternalChangeKind.MODIFIED))
        val deleted = tabItemOf(tab.copy(conflict = ExternalChangeKind.DELETED))

        assertSame(KarpfenIcons.Warning, modified.icon)
        assertSame(KarpfenIcons.Warning, deleted.icon)
        assertEquals("robot.kstates — robot.kstates changed on disk while it has unsaved changes.", modified.tooltip)
        assertEquals("robot.kstates — robot.kstates was deleted on disk.", deleted.tooltip)
        assertEquals("robot.kstates", modified.title)
    }
}
