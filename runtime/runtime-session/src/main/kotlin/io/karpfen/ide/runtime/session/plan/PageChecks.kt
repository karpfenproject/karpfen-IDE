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
package io.karpfen.ide.runtime.session.plan

import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.dsl.Flattening
import io.karpfen.ide.core.workspace.analysis.ProjectFragments
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import java.nio.file.Path

/**
 * Finds the fragments that a state machine of the run uses, directly or through other fragments, which the project
 * has but the descriptor does not list. The runtime holds the fragments the descriptor lists and nothing else, so it
 * would refuse to activate the environment. The language checks of dsl-tools only see the context of the descriptor,
 * in which such a fragment is unknown. This check names the fragment, the file it is written in and the file that uses
 * it, so the fix in the descriptor is plain.
 *
 * It walks from each attached state machine and each definition through the fragments their container states use,
 * listed or not, and reports each unlisted fragment once, at the descriptor, with the first file found to use it. A
 * name that no file of the project declares as a fragment is left to the language checks and to [CompositionCheck].
 */
internal class FragmentNotListedCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> {
        val context = subject.context ?: return emptyList()
        val listed = subject.fragments.mapNotNullTo(HashSet()) { context.stateMachines[it.file]?.name }
        val project = ProjectFragments(subject.analysis)
        val reported = LinkedHashMap<String, DeployProblem>()
        val visited = HashSet<String>()
        runs(subject).forEach { (root, machine) ->
            val pending = ArrayDeque(listOf(root to machine))
            while (pending.isNotEmpty()) {
                val (user, using) = pending.removeFirst()
                Compositions.usedFragments(using).forEach { name ->
                    if (!visited.add(name)) return@forEach
                    val declared = project.named(name, near = subject.descriptor) ?: return@forEach
                    if (name !in listed) reported[name] = problem(subject, name, declared.path, user, root)
                    readMachine(subject, declared.path)?.let { pending.addLast(declared.path to it) }
                }
            }
        }
        return reported.values.toList()
    }

    /** The attached state machines and definitions of [subject] with their files, each file once, in descriptor order. */
    private fun runs(subject: PlanSubject): List<Pair<Path, StateMachine>> =
        subject.machines.filter { !subject.isFragment(it.file) }.map { it.file }.distinct()
            .mapNotNull { file -> subject.context?.stateMachines?.get(file)?.let { file to it } }

    /** The state machine in [file] as dsl-tools read it in whatever context of the project holds the file. */
    private fun readMachine(subject: PlanSubject, file: Path): StateMachine? =
        subject.analysis.contexts.firstNotNullOfOrNull { it.stateMachines[file] }

    /** The problem that [user], run through [root], uses the fragment [name] of [file], which the descriptor does not list. */
    private fun problem(subject: PlanSubject, name: String, file: Path, user: Path, root: Path): DeployProblem {
        val by = if (user == root) subject.nameOf(user) else "${subject.nameOf(user)}, a page of ${subject.nameOf(root)},"
        return DeployProblem(
            Severity.ERROR,
            DeployProblemCodes.FRAGMENT_NOT_LISTED,
            "$by uses the fragment '$name' of ${subject.nameOf(file)}, but the descriptor does not list it; the runtime refuses " +
                "to activate the environment.",
            subject.descriptorLocation,
        )
    }
}

/**
 * Finds the attached state machines and definitions that do not flatten with the state machines the descriptor
 * lists. The runtime flattens each of them when it activates the environment and refuses the activation for every
 * problem, so the deployment stops here, before anything is uploaded. Each problem keeps the words of dsl-tools, which
 * are those of the runtime's refusal, and points at the state it concerns in the file it stands in, or at the file.
 *
 * A container state that uses a fragment the project has but the descriptor does not list is left out, because
 * [FragmentNotListedCheck] reports that cause. A problem that several state machines share, such as one inside a
 * fragment they both use, is reported once.
 */
internal class CompositionCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> {
        val project = ProjectFragments(subject.analysis)
        val files = subject.machines.filter { !subject.isFragment(it.file) }.map { it.file }.distinct()
        val problems = LinkedHashMap<Pair<Path?, String>, DeployProblem>()
        files.forEach { file ->
            val notFlat = subject.compositions.of(file) as? Flattening.NotFlat ?: return@forEach
            notFlat.findings
                .filter { finding -> finding.unknownFragment?.let { project.named(it, near = subject.descriptor) } == null }
                .forEach { finding ->
                    val where = subject.compositions.fileOf(finding.machine) ?: file
                    val location = finding.state?.let { subject.locations.stateIn(where, it) } ?: subject.machineLocation(where)
                    problems.putIfAbsent(
                        where to finding.message,
                        DeployProblem(
                            Severity.ERROR,
                            DeployProblemCodes.NOT_FLATTENED,
                            "${subject.nameOf(file)} cannot be flattened, so the runtime refuses to activate the environment: " +
                                "${finding.message}.",
                            location,
                        ),
                    )
                }
        }
        return problems.values.toList()
    }
}
