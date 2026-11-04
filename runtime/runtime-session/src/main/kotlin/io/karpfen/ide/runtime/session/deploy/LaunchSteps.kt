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
package io.karpfen.ide.runtime.session.deploy

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Waits for the connection to be open before a step the pushes of which the session must not miss: an ended
 * connection fails the step with its end, a wait that passes with a lost connection. Stateless.
 */
internal object ConnectionGate {
    /** The failure of the running step if the connection of [run] is not open within its wait, or null if it is. */
    suspend fun failureOf(run: DeployRun): StepOutcome.Failed? {
        val wait = run.timing.connectionWait
        val connection = run.connection ?: return StepOutcome.Failed(ConnectionProblems.notOpen(ConnectionState.Idle, wait), null)
        return when (val state = connection.awaitOpen(wait)) {
            is ConnectionState.Open -> null
            is ConnectionState.Ended -> StepOutcome.Failed(ConnectionProblems.ended(state.end), null)
            else -> StepOutcome.Failed(ConnectionProblems.notOpen(state, wait), null)
        }
    }
}

/** Activates the environment once the connection is open; the runtime freezes its configuration then. */
internal class ActivateStep : DeployStep {
    override val kind: DeployStepKind = DeployStepKind.ACTIVATE
    override val title: String = "Activating the environment"

    override suspend fun execute(run: DeployRun): StepOutcome {
        ConnectionGate.failureOf(run)?.let { return it }
        return run.settle(run.createdEnvironment.activate())
    }
}

/**
 * Switches observation on, or reads the model back once, and compares the model the runtime read with the model the
 * IDE analyzed: the differences become warnings of the deployment.
 *
 * @param observe switches observation on if true; otherwise the model is read back once.
 */
internal class ObserveStep(private val observe: Boolean) : DeployStep {
    override val kind: DeployStepKind = DeployStepKind.OBSERVE
    override val title: String = if (observe) "Switching observation on" else "Reading the model back"

    override suspend fun execute(run: DeployRun): StepOutcome {
        val observation = run.request.observation
        val outcome = if (observe) observation.switchOn() else observation.pull()
        return run.settle(outcome) { snapshot ->
            val differences = observation.differences(snapshot)
            val detail = when (differences.size) {
                0 -> "The runtime read the model as analyzed"
                1 -> "1 object differs"
                else -> "${differences.size} objects differ"
            }
            StepOutcome.Done(detail, differences)
        }
    }
}

/** Starts the environment once the connection is open. */
internal class StartStep : DeployStep {
    override val kind: DeployStepKind = DeployStepKind.START
    override val title: String = "Starting the environment"

    override suspend fun execute(run: DeployRun): StepOutcome {
        ConnectionGate.failureOf(run)?.let { return it }
        return run.settle(run.createdEnvironment.start())
    }
}

/**
 * Confirms the start by reading the model until every attached state machine has an active state. It reads once a
 * tick at most, every `max(confirmPoll, tick delay)`, for at most `max(confirmMinimum, confirmTicks × tick delay)`. An
 * engine that halted fails the step; a bound that passes adds an `engine-silent` warning, and the deployment still
 * succeeds. A read that fails is retried, unless the runtime no longer knows the environment.
 *
 * @param tickDelayMs the tick delay of the deploy configuration.
 */
internal class ConfirmStartStep(private val tickDelayMs: Int) : DeployStep {
    override val kind: DeployStepKind = DeployStepKind.CONFIRM_START
    override val title: String = "Confirming the start"

    override suspend fun execute(run: DeployRun): StepOutcome {
        val timing = run.timing
        val tick = tickDelayMs.milliseconds
        val poll = maxOf(timing.confirmPoll, tick)
        val bound = maxOf(timing.confirmMinimum, tick * timing.confirmTicks)
        val machines = run.plan.attachments.map { it.attachedTo }
        var silent = machines
        val confirmed = withTimeoutOrNull(bound) { awaitStates(run, machines, poll) { silent = it } }
        return confirmed ?: StepOutcome.Done("Not confirmed within $bound", listOf(silentWarning(silent, bound)))
    }

    /**
     * Reads the model every [poll] until every machine of [machines] has an active state, telling [waiting] which have
     * none yet; answers the outcome of the step once that happened, the engine halted or the environment is gone.
     */
    private suspend fun awaitStates(
        run: DeployRun,
        machines: List<String>,
        poll: Duration,
        waiting: (List<String>) -> Unit,
    ): StepOutcome {
        while (true) {
            when (val outcome = run.request.observation.pull()) {
                is CallOutcome.Done -> {
                    if (outcome.value.phase == RuntimePhase.HALTED) return halted()
                    val silent = silentIn(outcome.value, machines)
                    if (silent.isEmpty()) return StepOutcome.Done("Every state machine reported its state")
                    waiting(silent)
                    run.report("Waiting for ${silent.joinToString()}")
                }
                is CallOutcome.Failed -> if (outcome.failure.isLostEnvironment()) return run.settle(outcome)
                is CallOutcome.Refused -> return run.settle(outcome)
            }
            delay(poll)
        }
    }

    /** The attached keys of [machines] that have no active state in [snapshot]. */
    private fun silentIn(snapshot: RuntimeModelSnapshot, machines: List<String>): List<String> =
        machines.filter { snapshot.activeStates[it].isNullOrEmpty() }

    /** The failure of an engine that ended on its own. */
    private fun halted(): StepOutcome.Failed =
        StepOutcome.Failed(
            DeployProblem(
                Severity.ERROR,
                DeployProblemCodes.ENGINE_HALTED,
                "The engine ended on its own right after the start; the runtime's console log has the reason.",
                null,
            ),
            null,
        )

    /** The warning that the machines [silent] reported no state within [bound]. */
    private fun silentWarning(silent: List<String>, bound: Duration): DeployProblem =
        DeployProblem(
            Severity.WARNING,
            DeployProblemCodes.ENGINE_SILENT,
            "After the start, ${silent.joinToString { "'$it'" }} reported no active state within $bound.",
            null,
        )

    /** True if the runtime answered that it no longer knows the environment. */
    private fun RuntimeFailure.isLostEnvironment(): Boolean =
        this is RuntimeFailure.Rejected && kind == RejectionKind.UNKNOWN_ENVIRONMENT
}
