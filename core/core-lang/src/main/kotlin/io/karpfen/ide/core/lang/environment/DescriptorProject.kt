package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.environment.DescriptorFileRole
import io.karpfen.ide.core.document.environment.ValueShape
import io.karpfen.ide.core.document.environment.shapedValues
import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolDeclaration
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.analysis.AnalyzedFile
import io.karpfen.ide.core.workspace.environment.DescriptorPaths
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import java.nio.file.Path

/**
 * What the workspace knows about the files an environment descriptor names, for one request: the request's file tree
 * and latest project analysis, seen from the descriptor's directory. Paths are resolved and written by the workspace's
 * one rule ([DescriptorPaths]); what a file declares comes from the syntax index of its analyzed parse (the editor text
 * of an open file), so it is known even while the descriptor or the model does not convert. Immutable; one per request.
 */
internal class DescriptorProject(private val request: FeatureRequest) {

    /** The descriptor's directory, against which its paths are resolved. */
    private val directory: Path? = request.document.path.parent

    /** The file the descriptor names as [value], absolute and normalized, or null if [value] is no valid path. */
    fun resolve(value: String): Path? = directory?.let { DescriptorPaths.resolve(it, value) }

    /** True if [path] is a file of the workspace (in its tree, or analyzed). */
    fun exists(path: Path): Boolean = request.files?.find(path) is WorkspaceNode.File || request.project.file(path) != null

    /** The file the descriptor names as [value], as a navigation target at its start, or null if it does not exist. */
    fun locationOf(value: String): Location? {
        val path = resolve(value)?.takeIf(::exists) ?: return null
        return analyzed(path)?.let { Location(path, TextRange.EMPTY, it.snapshot) } ?: Location(path, TextRange.EMPTY)
    }

    /**
     * The workspace files a key of [role] can name ([FileKind.of] the role), as the descriptor names them: relative to
     * its directory with `/`. From the file tree, or from the analyzed files where no tree is known.
     */
    fun candidates(role: DescriptorFileRole): List<String> {
        val kind = FileKind.of(role)
        val base = directory ?: return emptyList()
        val paths = request.files?.files(kind)?.map { it.path }
            ?: request.project.files.keys.filter { FileKind.of(it.fileName?.toString().orEmpty()) == kind }
        return paths.map { DescriptorPaths.relative(base, it) }
    }

    /** The first file of [role] named below [node] (expected to have [shape]), resolved, or null. */
    fun fileNamed(role: DescriptorFileRole, node: JsonNode, shape: ValueShape.Record): Path? = filesNamed(role, node, shape).firstOrNull()

    /** Every file of [role] named below [node] (expected to have [shape]), resolved, in document order. */
    fun filesNamed(role: DescriptorFileRole, node: JsonNode, shape: ValueShape.Record): List<Path> =
        shapedValues(node, shape).mapNotNull { value ->
            val string = value.node as? JsonNode.StringNode
            if (string != null && (value.shape as? ValueShape.FilePath)?.role == role) resolve(string.value) else null
        }

    /** The named objects the model at [model] declares, nested ones included, in document order. */
    fun objectsOf(model: Path): List<SymbolDeclaration> =
        analyzed(model)?.parse?.index?.declarationsOf(SymbolKind.OBJECT)?.filter { it.name.isNotBlank() }.orEmpty()

    /** The object [key] declared in the model at [model], as a navigation target at its key, or null. */
    fun objectLocation(model: Path, key: String): Location? {
        val file = analyzed(model) ?: return null
        val declaration = file.parse.index.declarations(SymbolKind.OBJECT, key).firstOrNull() ?: return null
        return Location(model, declaration.nameRange, file.snapshot)
    }

    /** The type the state machine at [machine] names after `ATTACHED TO`, or null. */
    fun attachedTypeOf(machine: Path): String? =
        analyzed(machine)?.parse?.index?.declarationsOf(SymbolKind.STATEMACHINE)?.firstOrNull()?.typeName

    /** The event domains the state machines at [machines] use in `EVENT("domain", …)`, sorted, without repetitions. */
    fun eventDomainsOf(machines: List<Path>): List<String> =
        machines.flatMap { machine ->
            analyzed(machine)?.parse?.index?.referencesOf(ReferenceKind.EVENT_DOMAIN)?.map { it.name }.orEmpty()
        }.filter { it.isNotBlank() }.distinct().sorted()

    private fun analyzed(path: Path): AnalyzedFile? = request.project.file(path)
}
