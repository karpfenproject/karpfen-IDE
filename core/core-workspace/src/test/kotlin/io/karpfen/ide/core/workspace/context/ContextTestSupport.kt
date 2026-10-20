package io.karpfen.ide.core.workspace.context

import io.karpfen.ide.core.workspace.environment.DescriptorReadResult
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptorReader
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import java.nio.file.Path

/** The root of the hand-built trees. */
internal val WS_ROOT: Path = Path.of("/ws").toAbsolutePath().normalize()

/** The absolute path of [relative] below [WS_ROOT]. */
internal fun ws(relative: String): Path = WS_ROOT.resolve(relative).normalize()

/** A tree below [WS_ROOT] holding exactly [files] (`/`-separated relative paths), sorted like a scan. */
internal fun treeOf(vararg files: String): WorkspaceTree {
    fun build(directory: Path, entries: List<List<String>>): WorkspaceNode.Directory {
        val (here, nested) = entries.partition { it.size == 1 }
        val children = here.map { WorkspaceNode.File(directory.resolve(it[0]), it[0], FileKind.of(it[0])) } +
            nested.groupBy { it[0] }.map { (name, below) -> build(directory.resolve(name), below.map { it.drop(1) }) }
        return WorkspaceNode.Directory(directory, directory.fileName?.toString() ?: "/", children.sortedWith(WorkspaceNode.ORDER))
    }
    return WorkspaceTree(build(WS_ROOT, files.map { it.split('/') }))
}

/** Read results of descriptor texts by relative path. */
internal fun descriptors(vararg texts: Pair<String, String>): Map<Path, DescriptorReadResult> {
    val reader = EnvironmentDescriptorReader()
    return texts.associate { (relative, text) -> ws(relative) to reader.read(ws(relative), text) }
}

/** A minimal descriptor text. */
internal fun descriptorText(metamodel: String, model: String, vararg stateMachines: String, events: String? = null): String {
    val machines = stateMachines.joinToString(", ") { "{\"file\": \"$it\", \"attachedTo\": \"robot\"}" }
    val eventsEntry = events?.let { ", \"events\": \"$it\"" }.orEmpty()
    return "{\"metamodel\": \"$metamodel\", \"model\": \"$model\"$eventsEntry, \"stateMachines\": [$machines]}"
}
