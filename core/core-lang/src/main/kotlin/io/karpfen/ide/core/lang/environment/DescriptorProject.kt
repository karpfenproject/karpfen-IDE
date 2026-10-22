package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.environment.DescriptorFileRole
import io.karpfen.ide.core.document.environment.ValueShape
import io.karpfen.ide.core.document.environment.shapedValues
import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.analysis.AnalyzedFile
import io.karpfen.ide.core.workspace.analysis.ContextSymbols
import io.karpfen.ide.core.workspace.environment.DescriptorFiles
import io.karpfen.ide.core.workspace.environment.DescriptorPaths
import io.karpfen.ide.core.workspace.environment.NamedFile
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import java.nio.file.Path

/**
 * What the workspace knows about the files an environment descriptor names, for one request: the request's file tree
 * and latest project analysis, seen from the descriptor's directory. Paths are resolved and written by the workspace's
 * one rule ([DescriptorPaths]), and whether a named file is there is decided by the rule the workspace checks the
 * descriptor with ([DescriptorFiles], over the request's file tree), so hover, go to definition and completion never
 * treat a file as there that the descriptor's findings report missing. Without a file tree (services that know no
 * workspace) no named file is there. What a file declares comes from the workspace's [ContextSymbols], which read the
 * syntax index of each analyzed parse (the editor text of an open file), so it is known even while the descriptor or
 * the model does not convert. Immutable; one per request.
 */
internal class DescriptorProject(private val request: FeatureRequest) {

    /** The descriptor's directory, against which its paths are resolved. */
    private val directory: Path? = request.document.path.parent

    /** The rule for the files the descriptor names, over the request's file tree or, without one, an empty tree. */
    private val files = DescriptorFiles(request.files ?: WorkspaceTree.empty(directory ?: request.document.path))

    /** What the analyzed files declare and use: objects, `ATTACHED TO` types, event domains. */
    val symbols: ContextSymbols = ContextSymbols(request.project)

    /** The file the descriptor names as [value], absolute and normalized, or null if [value] is no valid path. */
    fun resolve(value: String): Path? = directory?.let { DescriptorPaths.resolve(it, value) }

    /** What [value], named for [role], refers to in the workspace. */
    fun lookUp(value: String, role: DescriptorFileRole): NamedFile =
        directory?.let { files.lookUp(it, value, role) } ?: NamedFile.NoPath

    /**
     * The file the descriptor names as [value] for [role], as a navigation target at its start, or null if the
     * workspace has no such file. A file of another language than the role's is a target too: it is there.
     */
    fun locationOf(value: String, role: DescriptorFileRole): Location? {
        val path = lookUp(value, role).file?.path ?: return null
        return analyzed(path)?.let { Location(path, TextRange.EMPTY, it.snapshot) } ?: Location(path, TextRange.EMPTY)
    }

    /**
     * The workspace files a key of [role] can name, as the descriptor names them (relative to its directory with `/`):
     * the files of the tree that fit the role ([DescriptorFiles.candidates]), each marked as the usual choice or not
     * ([FileKind.isUsualFor]). For a role that accepts any file, a bridge configuration, only the usual files are
     * offered, because a list of every file of the workspace would help nobody.
     */
    fun candidates(role: DescriptorFileRole): List<FileCandidate> {
        val base = directory ?: return emptyList()
        return files.candidates(role)
            .filter { role.language != null || it.kind.isUsualFor(role) }
            .map { FileCandidate(DescriptorPaths.relative(base, it.path), it.kind.isUsualFor(role)) }
    }

    /** The first file of [role] named below [node] (expected to have [shape]), resolved, or null. */
    fun fileNamed(role: DescriptorFileRole, node: JsonNode, shape: ValueShape.Record): Path? = filesNamed(role, node, shape).firstOrNull()

    /** Every file of [role] named below [node] (expected to have [shape]), resolved, in document order. */
    fun filesNamed(role: DescriptorFileRole, node: JsonNode, shape: ValueShape.Record): List<Path> =
        shapedValues(node, shape).mapNotNull { value ->
            val string = value.node as? JsonNode.StringNode
            if (string != null && (value.shape as? ValueShape.FilePath)?.role == role) resolve(string.value) else null
        }

    /** The object [key] declared in the model at [model], as a navigation target at its key, or null. */
    fun objectLocation(model: Path, key: String): Location? {
        val file = analyzed(model) ?: return null
        val declaration = file.parse.index.declarations(SymbolKind.OBJECT, key).firstOrNull() ?: return null
        return Location(model, declaration.nameRange, file.snapshot)
    }

    private fun analyzed(path: Path): AnalyzedFile? = request.project.file(path)
}

/**
 * A file a descriptor key can name.
 *
 * @property path the file as the descriptor names it, relative to its directory with `/`.
 * @property isUsual true if the file is the usual choice for the key's role, which completion offers first.
 */
internal data class FileCandidate(val path: String, val isUsual: Boolean)
