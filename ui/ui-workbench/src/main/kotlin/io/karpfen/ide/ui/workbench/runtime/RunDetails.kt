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
package io.karpfen.ide.ui.workbench.runtime

import io.karpfen.ide.runtime.session.ObservedDomainEvent
import io.karpfen.ide.runtime.session.RuntimeSession
import io.karpfen.ide.runtime.session.events.InjectionOutcome
import io.karpfen.ide.runtime.session.events.InjectionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One domain event of a run's event log.
 *
 * @property sequence numbers the events of one run in the order they arrived, starting at 1.
 * @property event the event as the session observed it.
 */
public data class DomainEventEntry(public val sequence: Long, public val event: ObservedDomainEvent)

/**
 * What the tabs of a launched run show beyond the flows of its [session]. That is the domain event log, the model tree
 * and the event injector. The log lists the newest event first and keeps at most [EVENT_LOG_LIMIT] events. The
 * injector sends through [send]. Everything is collected in a child scope of [parent] on the UI thread until [close].
 * The child scope has a supervisor job, so a failure in one collector does not cancel the others.
 *
 * @property session the session of the run.
 */
public class RunDetails internal constructor(
    public val session: RuntimeSession,
    parent: CoroutineScope,
    send: suspend (InjectionRequest) -> InjectionOutcome,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val log = MutableStateFlow<List<DomainEventEntry>>(emptyList())
    private var arrived = 0L

    /** The domain events of the run, newest first. */
    public val events: StateFlow<List<DomainEventEntry>> get() = log.asStateFlow()

    /** The tree of the observed model. */
    public val modelTree: ModelTreeState = ModelTreeState(scope, session.model)

    /** The event injector. */
    public val injector: InjectorState = InjectorState(session.events, scope, send)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.domainEvents.collect { event ->
                arrived++
                log.update { (listOf(DomainEventEntry(arrived, event)) + it).take(EVENT_LOG_LIMIT) }
            }
        }
    }

    /** Stops following the session. */
    internal fun close() {
        scope.cancel()
    }

    public companion object {
        /** How many domain events the log keeps. */
        public const val EVENT_LOG_LIMIT: Int = 500
    }
}
