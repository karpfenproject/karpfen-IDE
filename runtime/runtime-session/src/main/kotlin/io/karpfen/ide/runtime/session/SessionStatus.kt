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
package io.karpfen.ide.runtime.session

import io.karpfen.ide.runtime.session.deploy.DeployProgress
import io.karpfen.ide.runtime.session.events.InjectionAvailability
import io.karpfen.ide.runtime.session.problem.DeployProblem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What a session publishes about itself: its state with the injection availability that follows from it, the progress
 * of its deployment and its problems. The conductor is the one writer; closing the session seals the status, after
 * which the state stays [SessionState.Closed] whatever the conductor still publishes.
 *
 * Threading: every function may be called from any thread; the state and the availability change together, under one
 * lock, so a sealed status never shows a stale availability.
 *
 * @param planFindings the warnings and infos of the plan, the first problems of the session.
 */
internal class SessionStatus(planFindings: List<DeployProblem>) {
    /** Guards [sealed] and the writes of the state and the availability. */
    private val lock = Any()

    /** True once the session was closed. */
    private var sealed = false

    /** The writable state. */
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Deploying)

    /** The writable availability. */
    private val mutableAvailability = MutableStateFlow<InjectionAvailability>(InjectionAvailability.NotYet)

    /** The writable progress. */
    private val mutableProgress = MutableStateFlow(DeployProgress(emptyList(), null))

    /** The writable problems. */
    private val mutableProblems = MutableStateFlow(planFindings.toList())

    /** Where the session stands. */
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    /** Whether events can be injected now. */
    val availability: StateFlow<InjectionAvailability> = mutableAvailability.asStateFlow()

    /** The progress of the deployment. */
    val progress: StateFlow<DeployProgress> = mutableProgress.asStateFlow()

    /** The problems so far, oldest first. */
    val problems: StateFlow<List<DeployProblem>> = mutableProblems.asStateFlow()

    /** Publishes [state] with the injection [availability] that follows from it, unless the status is sealed. */
    fun publish(state: SessionState, availability: InjectionAvailability) {
        synchronized(lock) {
            if (sealed) return
            mutableState.value = state
            mutableAvailability.value = availability
        }
    }

    /** Publishes the deployment's [progress]. */
    fun progress(progress: DeployProgress) {
        mutableProgress.value = progress
    }

    /** Adds [problem] to the problems. */
    fun raise(problem: DeployProblem) {
        mutableProblems.update { it + problem }
    }

    /** Seals the status as closed and answers the state it had before; a second call answers [SessionState.Closed]. */
    fun seal(): SessionState =
        synchronized(lock) {
            val before = mutableState.value
            sealed = true
            mutableState.value = SessionState.Closed
            mutableAvailability.value = InjectionAvailabilities.of(SessionState.Closed, linkOpen = false)
            before
        }
}
