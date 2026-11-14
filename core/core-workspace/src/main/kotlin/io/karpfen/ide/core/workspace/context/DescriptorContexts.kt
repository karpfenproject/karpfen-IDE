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
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.environment.DescriptorFileRole
import io.karpfen.ide.core.document.environment.EnvironmentDescriptor
import io.karpfen.ide.core.document.environment.EnvironmentSchema
import io.karpfen.ide.core.workspace.diagnostics.DiagnosticsByFile
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.core.workspace.environment.DescriptorFileReference
import io.karpfen.ide.core.workspace.environment.DescriptorFiles
import io.karpfen.ide.core.workspace.environment.DescriptorReadResult
import io.karpfen.ide.core.workspace.environment.NamedFile
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import java.nio.file.Path

/**
 * The explicit contexts of a workspace, built by the rules on [ContextResolver]. There is one context per descriptor
 * that was read successfully, in path order.
 *
 * Whether a file that the descriptor names is there is decided by [DescriptorFiles], the same rule the editor follows.
 * A reference may name no file of the tree. The file may be missing, lie in an ignored directory or outside the
 * workspace, be a directory, or the value may not be a valid path. Such a reference is an ERROR
 * `descriptor-missing-file` on the descriptor at the range of the reference, and it is left out of the context. So the
 * analysis never reads a file that the descriptor reports as missing. Bridges are references too.
 *
 * A reference may also name a file that does not fit its role, because it is written in another language than the
 * role needs. An example is a `.kstates` file as the metamodel. That is an ERROR `descriptor-invalid` at the
 * reference. The context still names the file, since it is there, but the analysis reads nothing with dsl-tools that
 * depends on it. The read findings of the descriptors are reported as they are.
 */
internal class DescriptorContexts(private val tree: WorkspaceTree, private val descriptors: Map<Path, DescriptorReadResult>) {

    /** The rule for the files the descriptors name. */
    private val files = DescriptorFiles(tree)

    /** The explicit contexts. Findings go to [diagnostics]. */
    fun resolve(diagnostics: DiagnosticsByFile): List<ModelingContext> {
        val paths = (tree.files(FileKind.ENV_DESCRIPTOR).map { it.path } + descriptors.keys)
            .distinct()
            .sorted()
        return paths.mapNotNull { path ->
            val result = descriptors[path] ?: return@mapNotNull null
            diagnostics.add(path, result.diagnostics)
            val descriptor = result.descriptor ?: return@mapNotNull null
            val directory = checkNotNull(path.parent) { "descriptor $path has no directory" }
            result.references.forEach { reference -> check(reference, directory)?.let { diagnostics.add(path, it) } }
            contextOf(path, directory, descriptor, result.references.filter { it.role == DescriptorFileRole.STATE_MACHINE })
        }
    }

    /**
     * The context of the descriptor at [path] in [directory]. The files it names that are not there are left out. The
     * context carries the decoded [descriptor] itself, so the rest of what it says reaches the analysis too. Each state
     * machine carries where the descriptor names its file, from [machineReferences], the references of the role
     * STATE_MACHINE in document order, one per entry of `stateMachines`.
     */
    private fun contextOf(
        path: Path,
        directory: Path,
        descriptor: EnvironmentDescriptor,
        machineReferences: List<DescriptorFileReference>,
    ): ModelingContext {
        fun named(value: String, role: DescriptorFileRole): Path? = files.lookUp(directory, value, role).file?.path
        return ModelingContext(
            directory = directory,
            descriptor = path,
            metamodel = named(descriptor.metamodel, DescriptorFileRole.METAMODEL),
            events = descriptor.events?.let { named(it, DescriptorFileRole.EVENTS) },
            models = listOfNotNull(named(descriptor.model, DescriptorFileRole.MODEL)),
            stateMachines = descriptor.stateMachines.mapIndexedNotNull { index, entry ->
                named(entry.file, DescriptorFileRole.STATE_MACHINE)?.let {
                    StateMachineBinding(it, entry.attachedTo, machineReferences.getOrNull(index)?.range)
                }
            },
            environment = descriptor,
        )
    }

    /**
     * The finding on [reference] if it names a missing file or a file of the wrong language. Null if it names a
     * fitting file.
     */
    private fun check(reference: DescriptorFileReference, directory: Path): Diagnostic? =
        when (val named = files.lookUp(directory, reference.value, reference.role)) {
            NamedFile.NoPath, is NamedFile.Missing -> missingFile(reference)
            is NamedFile.WrongLanguage -> wrongLanguage(reference, named.language)
            is NamedFile.Present -> null
        }

    /** The key that names files of [role], written the way findings name it, such as `stateMachines[].file`. */
    private fun keyOf(role: DescriptorFileRole): String = EnvironmentSchema.keyPathOf(role)

    /** The ERROR `descriptor-missing-file` at [reference]. */
    private fun missingFile(reference: DescriptorFileReference): Diagnostic =
        Diagnostic(
            range = reference.range,
            severity = Severity.ERROR,
            message = "The file '${reference.value}' named by '${keyOf(reference.role)}' does not exist in the workspace.",
            code = WorkspaceDiagnosticCodes.DESCRIPTOR_MISSING_FILE,
            source = DiagnosticSource.IDE,
        )

    /** The ERROR `descriptor-invalid` at [reference], which names a file that is not written in [language]. */
    private fun wrongLanguage(reference: DescriptorFileReference, language: KarpfenLanguage): Diagnostic =
        Diagnostic(
            range = reference.range,
            severity = Severity.ERROR,
            message = "The file '${reference.value}' named by '${keyOf(reference.role)}' is not a ${language.displayName} file " +
                "(.${language.extension}).",
            code = WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID,
            source = DiagnosticSource.IDE,
        )
}
