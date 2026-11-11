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
 * The implicit contexts of a workspace, built by the rules on [ContextResolver]. Every directory of [tree] without a
 * descriptor forms one context. A directory that contains a `*.kenv.json`, readable or not, is covered by it and forms
 * none. The context holds the karpfen files directly inside the directory that are not [covered] by an explicit
 * context. If none are left, there is no context. The contexts come in directory path order.
 *
 * So a file that a descriptor in another directory names is analyzed once, in the context of that descriptor. It is
 * never reported as a file without a metamodel. Within a directory the files are taken in tree order. So
 * "alphabetically first" means the case-insensitive order of the tree, [WorkspaceNode.ORDER].
 *
 * The findings have the source IDE and lie at the start of the file, because the resolver works without file texts.
 * - Several domain metamodels each get an INFO `context-multiple-metamodels`.
 * - Models or state machines without any domain metamodel each get an INFO `context-no-metamodel`.
 */
internal class DirectoryContexts(private val tree: WorkspaceTree, private val covered: Set<Path>) {

    /** The implicit contexts. Findings go to [diagnostics]. */
    fun resolve(diagnostics: DiagnosticsByFile): List<ModelingContext> =
        directories(tree.root)
            .filter { directory -> directory.children.none { it is WorkspaceNode.File && it.kind == FileKind.ENV_DESCRIPTOR } }
            .mapNotNull { directory -> contextOf(directory, diagnostics) }
            .sortedBy { it.directory }

    /** The implicit context of [directory], or null if no uncovered karpfen file lies directly inside it. */
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
                        "so its models are not read and no domain types are known.",
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

    /** An INFO of the IDE at the start of the file. */
    private fun info(code: String, message: String): Diagnostic =
        Diagnostic(TextRange.EMPTY, Severity.INFO, message, code, DiagnosticSource.IDE)

    /** [directory] and every directory below it. */
    private fun directories(directory: WorkspaceNode.Directory): List<WorkspaceNode.Directory> =
        listOf(directory) + directory.children.filterIsInstance<WorkspaceNode.Directory>().flatMap(::directories)
}
