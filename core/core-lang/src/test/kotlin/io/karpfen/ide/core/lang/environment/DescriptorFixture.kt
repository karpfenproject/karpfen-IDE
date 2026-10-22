package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.fixtures.CARET
import io.karpfen.ide.core.lang.fixtures.ExamplePaths
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.lang.services.ProviderFailure
import io.karpfen.ide.core.lang.services.defaultLanguageServices
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Path
import java.util.Collections

/**
 * The example workspace of [ExampleProject] with an environment descriptor next to the single-robot files and a bridge
 * configuration, as the descriptor features see it: the analysis of the examples and a file tree of every example file
 * plus [BRIDGE] (the descriptor itself is the document under test, at [PATH]).
 */
internal object DescriptorFixture {

    /** Where the descriptor under test lives: in the single-robot example directory. */
    val PATH: Path = ExampleProject.pathOf("${ExamplePaths.ROBOT_DIR}/single_robot.kenv.json")

    /** A bridge configuration in the single-robot example directory. */
    val BRIDGE: Path = ExampleProject.pathOf("${ExamplePaths.ROBOT_DIR}/mqtt-sim.kbridge.json")

    /** The valid descriptor fixture's text. */
    val VALID: String = DescriptorExamples.text(DescriptorExamples.VALID)

    /** The file tree: every example file, [PATH] and [BRIDGE]. */
    val tree: WorkspaceTree by lazy { treeWith() }

    /** The file tree of every example file, [PATH], [BRIDGE] and the [extra] files. */
    fun treeWith(vararg extra: Path): WorkspaceTree = treeOf(exampleFiles() + extra)

    /** The file tree of every example file, [PATH] and [BRIDGE], without the [missing] files. */
    fun treeWithout(vararg missing: Path): WorkspaceTree = treeOf(exampleFiles() - missing.toSet())

    /**
     * The default services over the examples' analysis and [files], by default [tree]; [failures] collects every
     * provider failure.
     */
    fun services(
        failures: MutableList<ProviderFailure> = Collections.synchronizedList(ArrayList()),
        files: WorkspaceTree = tree,
    ): LanguageServices {
        val analysis = MutableStateFlow(ExampleProject.analysis)
        return defaultLanguageServices(analysis, onProviderFailure = { failures += it }, files = MutableStateFlow(files))
    }

    /** The descriptor document with [text] at [PATH]. */
    fun document(text: String, version: Long = 1): LanguageDocument = ExampleProject.document(PATH, text, version)

    /** The document of [textWithCaret] (the caret marked with `‸`) and the caret's offset. */
    fun at(textWithCaret: String): Pair<LanguageDocument, Int> {
        val offset = textWithCaret.indexOf(CARET)
        require(offset >= 0) { "no caret marker" }
        return document(textWithCaret.removeRange(offset, offset + 1)) to offset
    }

    /** Every example file, [PATH] and [BRIDGE]. */
    private fun exampleFiles(): List<Path> = Examples.files().map { ExampleProject.pathOf(it.path) } + listOf(PATH, BRIDGE)

    /** The tree below the examples' root holding exactly [files]. */
    private fun treeOf(files: List<Path>): WorkspaceTree = WorkspaceTree(directory(ExampleProject.ROOT, files))

    /** The directory node of [path] with every file of [files] below it. */
    private fun directory(path: Path, files: List<Path>): WorkspaceNode.Directory {
        val (direct, nested) = files.filter { it.startsWith(path) && it != path }.partition { it.parent == path }
        val directories = nested.groupBy { path.resolve(path.relativize(it).getName(0)) }.map { (child, inner) -> directory(child, inner) }
        val leaves = direct.map { WorkspaceNode.File(it, it.fileName.toString(), FileKind.of(it.fileName.toString())) }
        return WorkspaceNode.Directory(path, path.fileName?.toString() ?: path.toString(), (directories + leaves).sortedWith(WorkspaceNode.ORDER))
    }
}
