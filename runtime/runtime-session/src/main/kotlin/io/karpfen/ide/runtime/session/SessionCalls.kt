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

import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.connection.RuntimeConnection
import io.karpfen.ide.runtime.protocol.connection.SendOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.session.deploy.FailureLocator

/**
 * The calls behind a session's commands and events, and how their outcomes become the session's: the conductor decides
 * whether a command is allowed and settles the state afterwards; this class makes the call through the environment's
 * handle, the router or the connection and answers its outcome. A rejection by the runtime is a refusal with the
 * runtime's text; every failure is journaled. It also keeps the events sent without an observed reaction since the
 * last start or forced state, and notes before forcing a state that they may be discarded. Confined to the conductor.
 *
 * @param router the session's router, for the observation calls and the journal.
 * @param locator turns failures of the runtime into problems.
 * @param status the session's published status, whose state a refusal names.
 * @param clock the session's clock.
 */
internal class SessionCalls(
    private val router: PushRouter,
    private val locator: FailureLocator,
    private val status: SessionStatus,
    private val clock: SessionClock,
) {
    /** The events sent without an observed reaction since the last start or forced state. */
    private val unanswered = UnansweredEvents()

    /** Calls what [command] does for [environment]; never the stop, which the conductor runs on its own. */
    suspend fun perform(command: SessionCommand, environment: RuntimeEnvironment): SessionCommandOutcome =
        when (command) {
            SessionCommand.Start -> outcomeOf(environment.start()) { unanswered.reset() }
            is SessionCommand.ForceState -> {
                noteUnanswered()
                outcomeOf(environment.forceState(command.attachedTo, command.state)) { unanswered.reset() }
            }
            is SessionCommand.SetObservation -> if (command.on) outcomeOf(router.switchOn()) else outcomeOf(router.switchOff())
            SessionCommand.Pull -> outcomeOf(router.pull())
            SessionCommand.Stop -> error("the conductor stops the environment on its own")
        }

    /** Sends [frame] over [connection], journaling it and counting it as unanswered once written. */
    suspend fun send(frame: EventFrame, connection: RuntimeConnection?): SendOutcome {
        val outcome = connection?.send(frame) ?: return SendOutcome.NotConnected(ConnectionState.Idle)
        if (outcome is SendOutcome.Written) {
            router.record(SessionActivity.EventSent(outcome.frameText, clock.elapsed()))
            unanswered.sent(stacks())
        }
        return outcome
    }

    /** The outcome of a command whose call failed with [failure], journaled. */
    fun failed(failure: RuntimeFailure): SessionCommandOutcome {
        val problem = locator.unlocated(failure)
        router.record(SessionActivity.CallFailed(problem, clock.elapsed()))
        return if (failure is RuntimeFailure.Rejected && failure.kind != RejectionKind.UNKNOWN_ENVIRONMENT) {
            SessionCommandOutcome.Refused(status.state.value, failure.message)
        } else {
            SessionCommandOutcome.Failed(problem)
        }
    }

    /** The outcome of a command whose call ended with [call]; [done] runs on success. */
    private fun outcomeOf(call: CallOutcome<*>, done: () -> Unit = {}): SessionCommandOutcome =
        when (call) {
            is CallOutcome.Done -> {
                done()
                SessionCommandOutcome.Done
            }
            is CallOutcome.Refused -> SessionCommandOutcome.Refused(status.state.value, call.refusal.reason)
            is CallOutcome.Failed -> failed(call.failure)
        }

    /** Notes in the journal that events may be discarded by a forced state, if some got no observed reaction. */
    private fun noteUnanswered() {
        val count = unanswered.count(stacks())
        if (count == 0) return
        val events = if (count == 1) "1 event was" else "$count events were"
        router.record(
            SessionActivity.Note(
                "$events sent since the last start or forced state without any machine changing its state; forcing a " +
                    "state discards the events still queued, so they may have been discarded.",
                clock.elapsed(),
            ),
        )
    }

    /** The active stack of every machine as the observed model shows it now. */
    private fun stacks(): Map<String, List<String>> = router.model.value.states.machines.mapValues { it.value.stack }
}

/**
 * The events sent since the last start or forced state that got no observed reaction: as long as no machine changes its
 * active stack, every sent event counts; a change starts the count again with the next event. Confined to the conductor.
 */
internal class UnansweredEvents {
    /** The active stacks when the first counted event was sent, or null if none is counted. */
    private var since: Map<String, List<String>>? = null

    /** The counted events. */
    private var sent: Int = 0

    /** Counts an event sent while the machines had [stacks]. */
    fun sent(stacks: Map<String, List<String>>) {
        if (since == stacks) {
            sent++
        } else {
            since = stacks
            sent = 1
        }
    }

    /** The counted events if the machines still have [stacks], else 0: a change of a stack is a reaction. */
    fun count(stacks: Map<String, List<String>>): Int = if (since == stacks) sent else 0

    /** Forgets the counted events, after a start or a forced state. */
    fun reset() {
        since = null
        sent = 0
    }
}
