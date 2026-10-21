package io.karpfen.ide.core.workspace.context

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.diagnostics.DiagnosticsByFile
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import java.nio.file.Path

/**
 * The implicit contexts of a workspace (spec §4, rules on [ContextResolver]): every directory of [tree] without a
 * descriptor (a directory containing a `*.kenv.json`, readable or not, is covered by it) forms one of its karpfen files
 * directly inside that are not [covered] by an explicit context, if any are left; in directory path order. So a file
 * named by a descriptor in another directory is analyzed once, in the descriptor's context, and never reported as a
 * file without metamodel. Within a directory the files are taken in tree order, so "alphabetically first" is the
 * tree's case-insensitive order ([WorkspaceNode.ORDER]).
 *
 * Findings (source IDE, at the start of the file, since the resolver works without file texts):
 * - several domain metamodels → INFO `context-multiple-metamodels` on each of them;
 * - models or state machines without any domain metamodel → INFO `context-no-metamodel` on each of them.
 */
internal class DirectoryContexts(private val tree: WorkspaceTree, private val covered: Set<Path>) {

    /** The implicit contexts; findings go to [diagnostics]. */
    fun resolve(diagnostics: DiagnosticsByFile): List<ModelingContext> =
        directories(tree.root)
            .filter { directory -> directory.children.none { it is WorkspaceNode.File && it.kind == FileKind.ENV_DESCRIPTOR } }
            .mapNotNull { directory -> contextOf(directory, diagnostics) }
            .sortedBy { it.directory }

    private fun contextOf(directory: WorkspaceNode.Directory, diagnostics: DiagnosticsByFile): ModelingContext? {
        val files = directory.children.filterIsInstance<WorkspaceNode.File>().filter { it.kind.isKarpfenSource && it.path !in covered }
        if (files.isEmpty()) return null
        fun ofKind(kind: FileKind) = files.filter { it.kind == kind }
        val metamodels = ofKind(FileKind.KMETA)
        val chosen = metamodels.firstOrNull()
        val models = ofKind(FileKind.KMODEL)
        val machines = ofKind(FileKind.KSTATES)
        if (metamodels.size > 1) {
            val names = metamodels.joinToString(", ") { it.name }
            metamodels.forEach { metamodel ->
                val role = if (metamodel === chosen) "is used" else "is not analyzed with the directory's files"
                diagnostics.add(metamodel.path, info(
                    WorkspaceDiagnosticCodes.CONTEXT_MULTIPLE_METAMODELS,
                    "Directory '${directory.name}' contains several metamodels ($names); the first, ${chosen?.name}, " +
                        "forms its context, so ${metamodel.name} $role. Add an environment descriptor (*.kenv.json) to choose.",
                ))
            }
        }
        if (chosen == null) {
            (models + machines).forEach { file ->
                diagnostics.add(file.path, info(
                    WorkspaceDiagnosticCodes.CONTEXT_NO_METAMODEL,
                    "Directory '${directory.name}' has no metamodel (a .kmeta file other than ${FileKind.EVENTS_FILE_NAME}), " +
                        "so its models are not converted and no domain types are known.",
                ))
            }
        }
        return ModelingContext(
            directory = directory.path,
            descriptor = null,
            metamodel = chosen?.path,
            events = ofKind(FileKind.EVENTS_KMETA).firstOrNull()?.path,
            models = models.map { it.path },
            stateMachines = machines.map { StateMachineBinding(it.path, attachedTo = null) },
        )
    }

    private fun info(code: String, message: String): Diagnostic =
        Diagnostic(TextRange.EMPTY, Severity.INFO, message, code, DiagnosticSource.IDE)

    private fun directories(directory: WorkspaceNode.Directory): List<WorkspaceNode.Directory> =
        listOf(directory) + directory.children.filterIsInstance<WorkspaceNode.Directory>().flatMap(::directories)
}
