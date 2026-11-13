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
import io.karpfen.ide.core.lang.Location
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
import io.karpfen.ide.ui.workbench.problems.view.ProblemSection
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

        val top = nodes.roots.single { it.key == root }
        assertEquals(listOf("sub", "b.kstates", "c.txt"), top.children?.map { it.label })
        assertEquals(listOf(KarpfenIcons.Folder, KarpfenIcons.Kstates, KarpfenIcons.File), top.children?.map { it.icon })
        assertEquals(KarpfenIcons.Kmeta, top.children?.first()?.children?.single()?.icon)
        assertEquals(emptyList(), top.children?.first()?.children?.single()?.children.orEmpty(), "files are leaves")
        assertTrue(top.children?.first()?.children != null, "directories are expandable")
    }

    @Test
    fun `the project file and the descriptors come first, the bridge files last, each section below a line`() {
        fun file(path: String, kind: FileKind) = WorkspaceNode.File(root.resolve(path), path.substringAfterLast('/'), kind)
        val sub = WorkspaceNode.Directory(
            root.resolve("sub"), "sub",
            listOf(
                file("sub/.karpfenproject", FileKind.OTHER),
                file("sub/inner.kbridge.json", FileKind.BRIDGE),
                file("sub/inner.kenv.json", FileKind.ENV_DESCRIPTOR),
                file("sub/inner.kmodel", FileKind.KMODEL),
            ),
        )
        val files = listOf(
            file(".karpfenproject", FileKind.OTHER),
            file("http.kbridge.json", FileKind.BRIDGE),
            file("lamp.kenv.json", FileKind.ENV_DESCRIPTOR),
            file("lamp.kmeta", FileKind.KMETA),
        )
        val nodes = ProjectTreeNodes(WorkspaceTree(WorkspaceNode.Directory(root, "ws", listOf(sub) + files)))

        assertEquals(
            listOf("sub/inner.kenv.json", ".karpfenproject", "lamp.kenv.json", "ws", "sub/inner.kbridge.json", "http.kbridge.json"),
            nodes.roots.map { it.label },
        )
        assertEquals(listOf(false, false, false, true, true, false), nodes.roots.map { it.startsSection })
        val folder = nodes.roots.single { it.key == root }
        assertEquals(listOf("sub", "lamp.kmeta"), folder.children?.map { it.label })
        assertEquals(
            listOf(".karpfenproject", "inner.kmodel"), folder.children?.first()?.children?.map { it.label },
            "a project file further down belongs to another project and stays in its folder",
        )
    }

    @Test
    fun `a project without descriptors and bridges has one section and no line`() {
        val file = WorkspaceNode.File(root.resolve("a.kmeta"), "a.kmeta", FileKind.KMETA)
        val nodes = ProjectTreeNodes(WorkspaceTree(WorkspaceNode.Directory(root, "ws", listOf(file))))

        assertEquals(listOf(root), nodes.roots.map { it.key })
        assertFalse(nodes.roots.single().startsSection)
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
    fun `problem nodes group items per file below the Local and the Global node, with counts, severity icons and line col`() {
        val path = root.resolve("a.kmeta")
        fun item(line: Int, severity: Severity) =
            ProblemItem(path, Diagnostic(TextRange(0, 1), severity, "msg $line", "c", DiagnosticSource.PARSER), line, 2)
        val local = ProblemGroup(path, "a.kmeta", listOf(item(3, Severity.ERROR), item(7, Severity.HINT)))
        val global = ProblemGroup(path, "a.kmeta", listOf(item(9, Severity.WARNING).copy(version = 5)))
        val nodes = ProblemsTreeNodes(ProblemsUiState(local = listOf(local), global = listOf(global), hasFolder = true))

        assertEquals(listOf("Local (2)", "Global (1)"), nodes.roots.map { it.label })
        assertEquals(listOf(false, true), nodes.roots.map { it.startsSection })
        val (localNode, globalNode) = nodes.roots
        val group = localNode.children!!.single()
        assertEquals("a.kmeta (2)", group.label)
        assertEquals(listOf("msg 3 (3:2)", "msg 7 (7:2)"), group.children?.map { it.label })
        assertEquals(listOf(KarpfenIcons.Error, KarpfenIcons.Info), group.children?.map { it.icon })
        assertEquals("a.kmeta (1)", globalNode.children!!.single().label)
        assertEquals(listOf("msg 9 (9:2)"), globalNode.children!!.single().children?.map { it.label })
        assertEquals(listOf(KarpfenIcons.Warning), globalNode.children!!.single().children?.map { it.icon })

        val sections = listOf(ProblemSection.LOCAL, ProblemSection.GLOBAL)
        assertEquals(
            sections.flatMap { listOf(ProblemKey.Section(it), ProblemKey.Group(it, path)) }.toSet(),
            nodes.expandable,
            "the same file has a group below each top node, told apart by the section",
        )
        assertEquals(ProblemKey.Group(ProblemSection.GLOBAL, path), globalNode.children!!.single().key)
        assertEquals(7, nodes.itemOf(ProblemKey.Item(ProblemSection.LOCAL, path, 1))?.line)
        val globalItem = nodes.itemOf(ProblemKey.Item(ProblemSection.GLOBAL, path, 0))
        assertEquals(9, globalItem?.line)
        assertEquals(Location(path, TextRange(0, 1), 5), globalItem?.location, "a global item navigates to its range in the analyzed text")
        assertNull(nodes.itemOf(ProblemKey.Item(ProblemSection.GLOBAL, path, 1)))
        assertNull(nodes.itemOf(ProblemKey.Group(ProblemSection.LOCAL, path)))
        assertNull(nodes.itemOf(ProblemKey.Section(ProblemSection.GLOBAL)))
    }

    @Test
    fun `the Global node says when nothing was validated yet, and top nodes without problems are leaves`() {
        val notValidated = ProblemsTreeNodes(ProblemsUiState(hasFolder = true))
        assertEquals(listOf("Local (0)", "Global (not validated yet)"), notValidated.roots.map { it.label })
        assertEquals(listOf(null, null), notValidated.roots.map { it.children })
        assertEquals(emptySet(), notValidated.expandable)

        val clean = ProblemsTreeNodes(ProblemsUiState(global = emptyList(), hasFolder = true))
        assertEquals(listOf("Local (0)", "Global (0)"), clean.roots.map { it.label })
        assertNull(clean.roots[1].children)

        assertEquals(emptyList(), ProblemsTreeNodes(ProblemsUiState()).roots, "no nodes without an open folder")
    }
}
