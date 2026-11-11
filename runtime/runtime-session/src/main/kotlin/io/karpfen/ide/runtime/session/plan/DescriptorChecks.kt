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
 * Finds event domains named like an attached object key. Forcing the state of that object's machine makes the runtime
 * discard the domain's queued events.
 */
internal class DomainIsAttachmentCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> {
        val keys = subject.machines.mapNotNull { it.attachedTo }.toSet()
        return subject.domains.filter { it in keys }.map { domain ->
            DeployProblem(
                Severity.WARNING,
                DeployProblemCodes.DOMAIN_IS_ATTACHMENT,
                "The event domain '$domain' is named like the object a state machine is attached to: forcing that " +
                    "machine's state discards the domain's queued events.",
                subject.descriptorLocation,
            )
        }
    }
}

/**
 * Finds an `observe` key in the descriptor, which the deployment does not use. While observation is on, the IDE
 * observes the whole model, and it listens to every event domain the state machines use.
 */
internal class ObserveSupersededCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> {
        if (subject.environment?.observe == null) return emptyList()
        return listOf(
            DeployProblem(
                Severity.INFO,
                DeployProblemCodes.OBSERVE_SUPERSEDED,
                "The descriptor's observe key is not used: while observation is on, the IDE observes the whole model, and " +
                    "it listens to every event domain the state machines use.",
                subject.descriptorLocation,
            ),
        )
    }
}

/**
 * Finds the runtime's image, its Python packages or bridges in the descriptor. They concern hosting the runtime and
 * bridges, which the deployment does not do. It deploys to a runtime that is already running.
 */
internal class HostingIgnoredCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> {
        val descriptor = subject.environment ?: return emptyList()
        val keys = listOfNotNull(
            "runtime.image".takeIf { descriptor.runtime?.image != null },
            "runtime.pythonPackages".takeIf { descriptor.runtime?.pythonPackages.orEmpty().isNotEmpty() },
            "bridges".takeIf { descriptor.bridges.isNotEmpty() },
        )
        if (keys.isEmpty()) return emptyList()
        return listOf(
            DeployProblem(
                Severity.INFO,
                DeployProblemCodes.HOSTING_IGNORED,
                "${keys.joinToString()} concern hosting the runtime and bridges, which the deployment does not do yet; " +
                    "it deploys to a runtime that is already running.",
                subject.descriptorLocation,
            ),
        )
    }
}
