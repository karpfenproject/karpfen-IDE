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
 * What the workspace knows about the files an environment descriptor names, for one request. It holds the file tree
 * and the latest project analysis of the request, seen from the directory of the descriptor.
 *
 * Paths are resolved and written by the single rule of the workspace in [DescriptorPaths]. Whether a named file exists
 * is decided by [DescriptorFiles] over the file tree of the request. That is the same rule the workspace checks the
 * descriptor with. So hover, go to definition and completion never treat a file as present when the findings of the
 * descriptor report it missing. Services that know no workspace have no file tree, and then no named file exists.
 *
 * What a file declares comes from the [ContextSymbols] of the workspace. They read the syntax index of each analyzed
 * parse, which for an open file is the editor text. So the declarations are known even while the descriptor or the
 * model has errors. An instance is immutable and serves one request.
 */
internal class DescriptorProject(private val request: FeatureRequest) {

    /** The directory of the descriptor, against which its paths are resolved. */
    private val directory: Path? = request.document.path.parent

    /** The rule for the files the descriptor names, over the file tree of the request or else an empty tree. */
    private val files = DescriptorFiles(request.files ?: WorkspaceTree.empty(directory ?: request.document.path))

    /** What the analyzed files declare and use, such as objects, `ATTACHED TO` types and event domains. */
    val symbols: ContextSymbols = ContextSymbols(request.project)

    /** The file the descriptor names as [value], absolute and normalized, or null if [value] is no valid path. */
    fun resolve(value: String): Path? = directory?.let { DescriptorPaths.resolve(it, value) }

    /** What [value], named for [role], refers to in the workspace. */
    fun lookUp(value: String, role: DescriptorFileRole): NamedFile =
        directory?.let { files.lookUp(it, value, role) } ?: NamedFile.NoPath

    /**
     * The file the descriptor names as [value] for [role], as a navigation target at its start. It is null if the
     * workspace has no such file. A file in a language other than the one of the role is a target too, because it
     * exists.
     */
    fun locationOf(value: String, role: DescriptorFileRole): Location? {
        val path = lookUp(value, role).file?.path ?: return null
        return analyzed(path)?.let { Location(path, TextRange.EMPTY, it.snapshot) } ?: Location(path, TextRange.EMPTY)
    }

    /**
     * The workspace files a key of [role] can name, written as the descriptor names them, relative to its directory
     * with `/`. These are the files of the tree that fit the role, as [DescriptorFiles.candidates] finds them. Each is
     * marked by [FileKind.isUsualFor] as the usual choice or not. A bridge configuration accepts any file. For such a
     * role only the usual files are offered, because a list of every file of the workspace would help nobody.
     */
    fun candidates(role: DescriptorFileRole): List<FileCandidate> {
        val base = directory ?: return emptyList()
        return files.candidates(role)
            .filter { role.language != null || it.kind.isUsualFor(role) }
            .map { FileCandidate(DescriptorPaths.relative(base, it.path), it.kind.isUsualFor(role)) }
    }

    /** The first file of [role] named below [node], which should have [shape], resolved, or null. */
    fun fileNamed(role: DescriptorFileRole, node: JsonNode, shape: ValueShape.Record): Path? = filesNamed(role, node, shape).firstOrNull()

    /** Every file of [role] named below [node], which should have [shape], resolved and in document order. */
    fun filesNamed(role: DescriptorFileRole, node: JsonNode, shape: ValueShape.Record): List<Path> =
        shapedValues(node, shape).mapNotNull { value ->
            val string = value.node as? JsonNode.StringNode
            if (string != null && (value.shape as? ValueShape.FilePath)?.role == role) resolve(string.value) else null
        }

    /** The object [key] declared in the model file [model], as a navigation target at its key, or null. */
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
