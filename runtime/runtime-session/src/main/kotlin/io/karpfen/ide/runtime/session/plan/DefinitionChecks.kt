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

import io.karpfen.dsl.states.State
import io.karpfen.dsl.states.StateMachine
import io.karpfen.dsl.states.actions.ActionBlock
import io.karpfen.dsl.states.actions.ActionOperationType
import io.karpfen.dsl.states.actions.ActionRule
import io.karpfen.dsl.states.actions.InScopeBlock
import io.karpfen.dsl.states.actions.ValueActionRightSide
import io.karpfen.dsl.states.actions.WithBlock
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes

/**
 * Finds the types of the domain metamodel that behave after a state machine the descriptor does not list as a
 * definition. Whether a state machine of that name exists in the project at all is a language check of dsl-tools. This
 * check covers what only the deployment knows: the runtime holds the definitions the descriptor lists and nothing else,
 * and it refuses to activate an environment whose types behave after a name it does not hold.
 */
internal class DefinitionNotListedCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> {
        val metamodel = subject.context?.metamodel ?: return emptyList()
        val listed = subject.definitionNames
        return metamodel.types.mapNotNull { type ->
            val name = type.behavesAfter?.stateMachineName ?: return@mapNotNull null
            if (name in listed) return@mapNotNull null
            DeployProblem(
                Severity.ERROR,
                DeployProblemCodes.DEFINITION_NOT_LISTED,
                "'${type.name}' behaves after '$name', but the descriptor lists no definition of that name; the runtime " +
                    "refuses to activate the environment.",
                subject.descriptorLocation,
            )
        }
    }
}

/**
 * Finds `ATTACH` clauses and `EXCHANGE` actions in the state machines of the context that name, as a literal, a state
 * machine the descriptor does not list as a definition. The runtime fails such a rule when it runs and writes the
 * failure to its trace, so the rest of the run goes on. A name computed by a macro or an EVAL is only known when the
 * rule runs and is not checked. Each name is reported once per state machine file.
 */
internal class AttachedDefinitionNotListedCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> {
        val listed = subject.definitionNames
        return subject.machines.map { it.file }.distinct().flatMap { file ->
            val machine = subject.context?.stateMachines?.get(file) ?: return@flatMap emptyList()
            LiteralAttachments.namesIn(machine).filter { it !in listed }.map { name ->
                DeployProblem(
                    Severity.WARNING,
                    DeployProblemCodes.ATTACHED_DEFINITION_NOT_LISTED,
                    "${subject.nameOf(file)} attaches '$name', but the descriptor lists no definition of that name; the " +
                        "runtime fails that action when it runs.",
                    subject.machineLocation(file),
                )
            }
        }
    }
}

/** The literal state machine names that the `ATTACH` clauses and `EXCHANGE` actions of a state machine name. Stateless. */
internal object LiteralAttachments {
    /** The literal names [machine] attaches or exchanges to, distinct, in the order of its states and actions. */
    fun namesIn(machine: StateMachine): List<String> =
        flattened(machine.states).flatMap { listOf(it.onEntry, it.onDo) }.flatMap(::rulesIn).mapNotNull(::literalOf).distinct()

    /** The literal name [rule] attaches, or null if it attaches nothing or computes the name. */
    private fun literalOf(rule: ActionRule): String? {
        val named = if (rule.operationType == ActionOperationType.EXCHANGE) rule.rightSide else rule.attach?.stateMachineName
        return (named as? ValueActionRightSide)?.value
    }

    /** Every action rule of [block], with the rules nested in its `WITH` and `IN SCOPE` blocks. */
    private fun rulesIn(block: ActionBlock): List<ActionRule> = block.items.flatMap { item ->
        when (item) {
            is ActionRule -> listOf(item)
            is WithBlock -> rulesIn(item.body)
            is InScopeBlock -> rulesIn(item.body)
        }
    }

    /** [states] with all their inner states, depth first. */
    private fun flattened(states: List<State>): List<State> = states.flatMap { listOf(it) + flattened(it.innerStates) }
}
