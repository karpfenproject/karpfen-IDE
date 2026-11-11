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
package io.karpfen.ide.ui.workbench.projecttree.view

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import io.karpfen.ide.ui.workbench.outline.view.OutlineKey
import io.karpfen.ide.ui.workbench.outline.view.OutlineTreeNodes
import io.karpfen.ide.ui.workbench.problems.ProblemGroup
import io.karpfen.ide.ui.workbench.problems.ProblemItem
import io.karpfen.ide.ui.workbench.problems.ProblemsUiState
import io.karpfen.ide.ui.workbench.problems.view.ProblemKey
import io.karpfen.ide.ui.workbench.problems.view.ProblemsTreeNodes
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Tests the `TreeView` node mappings of the Project, Outline and Problems views. */
class TreeNodeMappingTest {

    private val root: Path = Path.of("/ws").toAbsolutePath()

    @Test
    fun `project nodes keep the tree's order and pick icons by file kind`() {
        val sub = WorkspaceNode.Directory(
            root.resolve("sub"), "sub",
            listOf(WorkspaceNode.File(root.resolve("sub/EVENTS.kmeta"), "EVENTS.kmeta", FileKind.EVENTS_KMETA)),
        )
        val files = listOf("a.kenv.json" to FileKind.ENV_DESCRIPTOR, "b.kstates" to FileKind.KSTATES, "c.txt" to FileKind.OTHER)
            .map { (name, kind) -> WorkspaceNode.File(root.resolve(name), name, kind) }
        val nodes = ProjectTreeNodes(WorkspaceTree(WorkspaceNode.Directory(root, "ws", listOf(sub) + files)))

        val top = nodes.roots.single()
        assertEquals(root, top.key)
        assertEquals(listOf("sub", "a.kenv.json", "b.kstates", "c.txt"), top.children?.map { it.label })
        assertEquals(listOf(KarpfenIcons.Folder, KarpfenIcons.Json, KarpfenIcons.Kstates, KarpfenIcons.File), top.children?.map { it.icon })
        assertEquals(KarpfenIcons.Kmeta, top.children?.first()?.children?.single()?.icon)
        assertEquals(emptyList(), top.children?.first()?.children?.single()?.children.orEmpty(), "files are leaves")
        assertTrue(top.children?.first()?.children != null, "directories are expandable")
    }

    @Test
    fun `outline nodes are keyed by index path, labelled with details and find the highlighted node`() {
        val inner = OutlineNode(SymbolKind.STATE, "inner", null, TextRange(5, 9), TextRange(6, 8), emptyList())
        val outer = OutlineNode(SymbolKind.STATE, "outer", null, TextRange(0, 10), TextRange(1, 4), listOf(inner))
        val type = OutlineNode(SymbolKind.TYPE, "Robot", "A robot", TextRange(11, 20), TextRange(12, 17), emptyList())
        val nodes = OutlineTreeNodes(listOf(outer, type))

        assertEquals(listOf("outer", "Robot : A robot"), nodes.roots.map { it.label })
        assertEquals(OutlineKey(listOf(0, 0)), nodes.roots.first().children?.single()?.key)
        assertNull(nodes.roots[1].children, "a leaf is not expandable")
        assertEquals(setOf(OutlineKey(listOf(0))), nodes.expandable)
        assertSame(inner, nodes.nodeOf(OutlineKey(listOf(0, 0))))
        assertNull(nodes.nodeOf(OutlineKey(listOf(3))))
        assertEquals(OutlineKey(listOf(0, 0)), nodes.keyOf(listOf(outer, inner)))
        assertNull(nodes.keyOf(emptyList()))
    }

    @Test
    fun `problem nodes group items per file with counts, severity icons and line col`() {
        val path = root.resolve("a.kmeta")
        fun item(line: Int, severity: Severity) =
            ProblemItem(path, Diagnostic(TextRange(0, 1), severity, "msg $line", "c", DiagnosticSource.PARSER), line, 2)
        val state = ProblemsUiState(groups = listOf(ProblemGroup(path, "a.kmeta", listOf(item(3, Severity.ERROR), item(7, Severity.HINT)))))
        val nodes = ProblemsTreeNodes(state)

        val group = nodes.roots.single()
        assertEquals("a.kmeta (2)", group.label)
        assertEquals(listOf("msg 3 (3:2)", "msg 7 (7:2)"), group.children?.map { it.label })
        assertEquals(listOf(KarpfenIcons.Error, KarpfenIcons.Info), group.children?.map { it.icon })
        assertEquals(setOf<ProblemKey>(ProblemKey.Group(path)), nodes.groups)
        assertEquals(7, nodes.itemOf(ProblemKey.Item(path, 1))?.line)
        assertNull(nodes.itemOf(ProblemKey.Group(path)))
    }
}
