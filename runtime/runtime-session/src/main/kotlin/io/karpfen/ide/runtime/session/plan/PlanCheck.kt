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

import io.karpfen.ide.runtime.session.problem.DeployProblem

/**
 * One check of a planned deployment. It is a rule that the subject must satisfy, in the style of the specification
 * design pattern. It returns the problems it finds, and none if the subject satisfies it. Checks are pure and
 * independent of each other. A check whose input is missing finds nothing. Examples are a missing context or a model
 * that was not read. The check that covers the missing input reports it.
 */
internal fun interface PlanCheck {
    /** The problems of [subject] this check finds, in a stable order. */
    fun check(subject: PlanSubject): List<DeployProblem>
}

/** The checks the planner runs, in the order their findings are reported. Stateless. */
internal object PlanChecks {
    /**
     * The problems gate. It requires a context to deploy, the saved state of its files and the descriptor, no error
     * in the analyzed texts, and a metamodel and a model that were read. Only a subject that passes the gate is checked
     * further.
     */
    fun gate(): List<PlanCheck> = listOf(NoContextCheck(), UnsavedFileCheck(), ContextErrorsCheck(), MissingInputsCheck())

    /** The preflight checks for what the runtime would mishandle or do unexpectedly, run after the gate passed. */
    fun preflight(): List<PlanCheck> = listOf(
        DefinitionsCheck(),
        UnknownAttachmentCheck(),
        DuplicateAttachmentCheck(),
        AttachmentTypeCheck(),
        TickDelayRangeCheck(),
        TickDelayZeroCheck(),
        EventTtlCheck(),
        ReferenceLoopCheck(),
        DomainIsAttachmentCheck(),
        ObserveSupersededCheck(),
        HostingIgnoredCheck(),
    )
}
