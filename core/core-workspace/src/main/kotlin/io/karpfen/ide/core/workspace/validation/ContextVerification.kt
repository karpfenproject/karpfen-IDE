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
package io.karpfen.ide.core.workspace.validation

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.dsl.DslVerifier
import io.karpfen.ide.core.document.dsl.MachineToVerify
import io.karpfen.ide.core.document.dsl.VerificationInput
import io.karpfen.ide.core.workspace.analysis.ContextAnalysis
import java.nio.file.Path

/**
 * Verifies the state machines and the metamodels of one modeling context with the [verifier] of dsl-tools, over the
 * reads the project analysis kept in `ContextAnalysis.reads`. So the verification works on exactly the texts the
 * analysis read, and no file is read again.
 *
 * The verifier gets the read of the domain metamodel, the read of the event metamodel if the context names one, the
 * read of the model if the context names exactly one, and the read of every state machine of the context: the attached
 * ones and the attachable ones, which the `behaves after` clauses, `ATTACH` and `EXCHANGE` name. A machine of a context
 * with a descriptor carries the object key the descriptor attaches it to. An attachable machine, and every machine of
 * a context without a descriptor, has no object, so the checks that need one stay silent for it. A machine that the
 * context names twice with the same object, or twice without one, is verified once, so its name is not reported as
 * taken by itself. A file the analysis did not read, because it has syntax errors or depends on a metamodel with
 * errors, has no read and is left out. The verifier learns how many machines are left out that way, and reports no
 * name as unknown while the set of attachable machines is incomplete.
 *
 * A context is not verified at all if its domain metamodel has no read, since every check builds on it. It is not
 * verified either if it names an event metamodel that has no read while its domain metamodel was read without errors.
 * The events file then has syntax errors of its own, and without its types the verifier would report the event types
 * a machine names as unknown. The class holds no state.
 */
internal class ContextVerification(private val verifier: DslVerifier) {

    /**
     * The findings of [context], each list with the path of the file it concerns: those of the state machines in the
     * order of the context, then those of the domain metamodel and those of the event metamodel. A machine that the
     * context names twice, attached to two objects, appears twice.
     */
    fun findings(context: ContextAnalysis): List<Pair<Path, List<Diagnostic>>> {
        val reads = context.reads
        val metamodel = reads.metamodel ?: return emptyList()
        val modeling = context.context
        if (modeling.events != null && reads.events == null && !metamodel.hasErrors) return emptyList()
        val bindings = modeling.stateMachines.distinctBy { it.file to it.attachedTo }
        val machines = bindings.mapNotNull { binding ->
            reads.stateMachines[binding.file]?.let { binding.file to MachineToVerify(it, binding.attachedTo) }
        }
        val unread = bindings.map { it.file }.distinct().count { it !in reads.stateMachines }
        val model = modeling.models.singleOrNull()?.let { reads.models[it] }
        val findings = verifier.verify(VerificationInput(metamodel, reads.events, model, machines.map { it.second }, unread))
        return machines.map { it.first }.zip(findings.stateMachines) +
            listOfNotNull(modeling.metamodel?.let { it to findings.metamodel }, modeling.events?.let { it to findings.events })
    }
}
