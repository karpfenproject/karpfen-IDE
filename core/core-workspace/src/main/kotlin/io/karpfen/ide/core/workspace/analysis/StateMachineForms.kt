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
package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.context.ModelingContext
import io.karpfen.ide.core.workspace.context.StateMachineBinding
import io.karpfen.ide.core.workspace.diagnostics.DiagnosticsByFile
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import java.nio.file.Path

/**
 * Checks that every state machine of an explicit context has the form its descriptor entry asks for. An entry with
 * `attachedTo` attaches the machine to an object, so the file must have the attached form `STATEMACHINE ATTACHED TO`.
 * An entry without it hands the machine to the environment as a definition, so the file must have the attachable form
 * `STATEMACHINE "name" ATTACHABLE TO`. The wrong form for either case is an ERROR `descriptor-state-machine-form` on
 * the descriptor at the file value of the entry. The runtime refuses such an upload, and the check tells it before a
 * run.
 *
 * A definition listed twice is a WARNING with the same code at the second entry, because the environment keeps one
 * definition per name and the second upload only replaces the first.
 *
 * The form comes from the IDE's own syntax index of the file, so the check works while dsl-tools cannot read it. A file
 * whose header has no form yet, or that the analysis could not parse, is left alone. Implicit contexts carry no
 * entries and are not checked.
 *
 * @property diagnostics where the findings go.
 */
internal class StateMachineForms(private val diagnostics: DiagnosticsByFile) {

    /** Checks the state machines of [context], whose parsed files are [files]. */
    fun check(context: ModelingContext, files: Map<Path, AnalyzedFile>) {
        val descriptor = context.descriptor ?: return
        val definitions = HashSet<Path>()
        for (binding in context.stateMachines) {
            val range = binding.reference ?: continue
            val attachable = isAttachable(files[binding.file]) ?: continue
            val finding = when {
                binding.attachedTo != null && attachable -> attachableAttached(binding, range)
                binding.attachedTo == null && !attachable -> attachedWithoutObject(range)
                binding.attachedTo == null && !definitions.add(binding.file) -> listedTwice(range)
                else -> null
            }
            finding?.let { diagnostics.add(descriptor, it) }
        }
    }

    /**
     * True if the machine of [file] has the attachable form, false if it has the attached form, and null if its form is
     * unknown, because the file is missing, not parsed or declares no machine.
     */
    private fun isAttachable(file: AnalyzedFile?): Boolean? =
        file?.parse?.index?.declarationsOf(SymbolKind.STATEMACHINE)?.firstOrNull()?.isAttachableStateMachine

    private fun attachableAttached(binding: StateMachineBinding, range: TextRange): Diagnostic =
        error(
            range,
            "The state machine is attachable and becomes a definition, so its entry has no 'attachedTo'. " +
                "To attach it to '${binding.attachedTo}', give it the form STATEMACHINE ATTACHED TO.",
        )

    private fun attachedWithoutObject(range: TextRange): Diagnostic =
        error(
            range,
            "The state machine is attached to a type and needs an object, so its entry needs 'attachedTo'. " +
                "To hand it over as a definition, give it the form STATEMACHINE \"name\" ATTACHABLE TO.",
        )

    private fun listedTwice(range: TextRange): Diagnostic =
        Diagnostic(
            range = range,
            severity = Severity.WARNING,
            message = "The definition is listed twice. The environment keeps one definition per name.",
            code = WorkspaceDiagnosticCodes.DESCRIPTOR_STATE_MACHINE_FORM,
            source = DiagnosticSource.IDE,
        )

    private fun error(range: TextRange, message: String): Diagnostic =
        Diagnostic(
            range = range,
            severity = Severity.ERROR,
            message = message,
            code = WorkspaceDiagnosticCodes.DESCRIPTOR_STATE_MACHINE_FORM,
            source = DiagnosticSource.IDE,
        )
}
