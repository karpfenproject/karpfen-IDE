package io.karpfen.ide.core.workspace.context

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.diagnostics.DiagnosticsByFile
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.core.workspace.environment.DescriptorFileReference
import io.karpfen.ide.core.workspace.environment.DescriptorFileRole
import io.karpfen.ide.core.workspace.environment.DescriptorReadResult
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptor
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The explicit contexts of a workspace (rules on [ContextResolver]): one per successfully read descriptor, in path
 * order, with paths resolved against the descriptor's directory (sub-directories and `..` allowed; the result is
 * normalized). Every reference that names no file of the tree — missing, in an ignored directory, outside the
 * workspace, a directory, or not a valid path — is an ERROR `descriptor-missing-file` on the descriptor at the
 * reference's range; bridges are references too. The descriptors' read findings are reported as they are.
 */
internal class DescriptorContexts(private val tree: WorkspaceTree, private val descriptors: Map<Path, DescriptorReadResult>) {

    /** The explicit contexts; findings go to [diagnostics]. */
    fun resolve(diagnostics: DiagnosticsByFile): List<ModelingContext> {
        val paths = (tree.files(FileKind.ENV_DESCRIPTOR).map { it.path } + descriptors.keys)
            .distinct()
            .sorted()
        return paths.mapNotNull { path ->
            val result = descriptors[path] ?: return@mapNotNull null
            diagnostics.add(path, result.diagnostics)
            val descriptor = result.descriptor ?: return@mapNotNull null
            val directory = checkNotNull(path.parent) { "descriptor $path has no directory" }
            result.references.filterNot { exists(directory, it.value) }.forEach { diagnostics.add(path, missingFile(it)) }
            contextOf(path, directory, descriptor)
        }
    }

    private fun contextOf(path: Path, directory: Path, descriptor: EnvironmentDescriptor): ModelingContext =
        ModelingContext(
            directory = directory,
            descriptor = path,
            metamodel = resolve(directory, descriptor.metamodel),
            events = descriptor.events?.let { resolve(directory, it) },
            models = listOfNotNull(resolve(directory, descriptor.model)),
            stateMachines = descriptor.stateMachines.mapNotNull { entry ->
                resolve(directory, entry.file)?.let { StateMachineBinding(it, entry.attachedTo) }
            },
        )

    private fun exists(directory: Path, value: String): Boolean =
        resolve(directory, value)?.let { tree.find(it) is WorkspaceNode.File } ?: false

    private fun resolve(directory: Path, value: String): Path? =
        try {
            directory.resolve(value).toAbsolutePath().normalize()
        } catch (e: InvalidPathException) {
            null
        }

    private fun missingFile(reference: DescriptorFileReference): Diagnostic =
        Diagnostic(
            range = reference.range,
            severity = Severity.ERROR,
            message = "The file '${reference.value}' named by '${keyOf(reference.role)}' does not exist in the workspace.",
            code = WorkspaceDiagnosticCodes.DESCRIPTOR_MISSING_FILE,
            source = DiagnosticSource.IDE,
        )

    private fun keyOf(role: DescriptorFileRole): String = when (role) {
        DescriptorFileRole.METAMODEL -> "metamodel"
        DescriptorFileRole.EVENTS -> "events"
        DescriptorFileRole.MODEL -> "model"
        DescriptorFileRole.STATE_MACHINE -> "stateMachines[].file"
        DescriptorFileRole.BRIDGE -> "bridges[]"
    }
}
