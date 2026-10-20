package io.karpfen.ide.core.workspace

import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.context.ModelingContext
import io.karpfen.ide.core.workspace.context.StateMachineBinding
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract test of the complete core-workspace values (no phase-1 implementation code is executed). */
class WorkspaceContractTest {

    private val root: Path = Path.of("/ws").toAbsolutePath()

    @Test
    fun `file kinds come from names`() {
        assertEquals(FileKind.EVENTS_KMETA, FileKind.of("events.KMETA"))
        assertEquals(FileKind.KMETA, FileKind.of("robot.kmeta"))
        assertEquals(FileKind.KSTATES, FileKind.of("robot.kstates"))
        assertEquals(FileKind.ENV_DESCRIPTOR, FileKind.of("robot.kenv.json"))
        assertEquals(FileKind.LAYOUT, FileKind.of("robot.kstates.layout.json"))
        assertEquals(FileKind.OTHER, FileKind.of("README.md"))
        assertTrue(FileKind.EVENTS_KMETA.isKarpfenSource)
    }

    @Test
    fun `trees list directories first in case-insensitive order and find nodes`() {
        val file = WorkspaceNode.File(root.resolve("b/robot.kmeta"), "robot.kmeta", FileKind.KMETA)
        val directory = WorkspaceNode.Directory(root.resolve("b"), "b", listOf(file))
        val readme = WorkspaceNode.File(root.resolve("A.md"), "A.md", FileKind.OTHER)
        val tree = WorkspaceTree(WorkspaceNode.Directory(root, "ws", listOf(directory, readme)))

        assertEquals(listOf(file, readme), tree.files())
        assertEquals(file, tree.find(root.resolve("b/robot.kmeta")))
        assertNull(tree.find(root.resolve("c")))
        assertFailsWith<IllegalArgumentException> { WorkspaceNode.Directory(root, "ws", listOf(readme, directory)) }
    }

    @Test
    fun `contexts and analyses answer membership queries`() {
        val machine = root.resolve("robot.kstates")
        val context = ModelingContext(
            directory = root,
            descriptor = null,
            metamodel = root.resolve("robot.kmeta"),
            events = null,
            models = listOf(root.resolve("robot.kmodel")),
            stateMachines = listOf(StateMachineBinding(machine, attachedTo = null)),
        )

        assertTrue(context.isImplicit)
        assertTrue(machine in context)
        assertEquals(3, context.files.size)
        assertEquals(listOf(context), ContextResolution(listOf(context), emptyMap()).contextsOf(machine))
        assertNull(ProjectAnalysis.EMPTY.contextOf(machine))
        assertEquals(emptyList(), ProjectAnalysis.EMPTY.diagnosticsFor(machine))
    }

    @Test
    fun `workspace options validate durations`() {
        assertTrue(".git" in WorkspaceOptions.DEFAULT_IGNORED_DIRECTORIES)
        assertFailsWith<IllegalArgumentException> {
            WorkspaceOptions(analysisDebounce = kotlin.time.Duration.parse("-1s"))
        }
    }
}
