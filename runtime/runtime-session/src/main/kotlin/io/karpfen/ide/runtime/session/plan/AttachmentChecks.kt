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

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes

/**
 * Finds state machines attached to a key that no object of the runtime's index of the model has: the runtime refuses
 * to activate such an environment.
 */
internal class UnknownAttachmentCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> {
        val keyed = subject.keyedObjects ?: return emptyList()
        val model = subject.modelFile?.let(subject::nameOf) ?: return emptyList()
        return subject.machines.filter { it.attachedTo.isNullOrBlank() || it.attachedTo !in keyed.ids }.map { machine ->
            DeployProblem(
                Severity.ERROR,
                DeployProblemCodes.UNKNOWN_ATTACHMENT,
                "${subject.nameOf(machine.file)} is attached to '${machine.attachedTo.orEmpty()}', but $model has no object " +
                    "with that key; the runtime refuses to activate the environment.",
                subject.descriptorLocation,
            )
        }
    }
}

/** Finds keys with more than one state machine attached: the runtime would keep only the last, silently. */
internal class DuplicateAttachmentCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> =
        subject.machines.filter { !it.attachedTo.isNullOrBlank() }.groupBy { it.attachedTo.orEmpty() }
            .filterValues { it.size > 1 }
            .map { (key, machines) ->
                DeployProblem(
                    Severity.ERROR,
                    DeployProblemCodes.DUPLICATE_ATTACHMENT,
                    "${machines.size} state machines are attached to '$key' (${machines.joinToString { subject.nameOf(it.file) }}); " +
                        "the runtime would keep only the last.",
                    subject.descriptorLocation,
                )
            }
}

/**
 * Finds state machines attached to an object whose type differs from the type the machine names after `ATTACHED TO`;
 * the runtime never reads that type, so the machine runs anyway, on an object it may not fit.
 */
internal class AttachmentTypeCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> {
        val keyed = subject.keyedObjects ?: return emptyList()
        return subject.machines.mapNotNull { machine ->
            val key = machine.attachedTo ?: return@mapNotNull null
            val objectType = keyed.typeOf(key) ?: return@mapNotNull null
            val machineType = subject.context?.stateMachines?.get(machine.file)?.attachedToClass ?: return@mapNotNull null
            if (objectType == machineType) return@mapNotNull null
            DeployProblem(
                Severity.WARNING,
                DeployProblemCodes.ATTACHMENT_TYPE,
                "${subject.nameOf(machine.file)} is written for '$machineType' but attached to '$key' of type '$objectType'; " +
                    "the runtime runs it anyway.",
                subject.machineLocation(machine.file),
            )
        }
    }
}
