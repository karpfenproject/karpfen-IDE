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

import io.karpfen.ide.runtime.host.HostProblem
import io.karpfen.ide.runtime.host.HostStage
import io.karpfen.ide.runtime.host.HostStatus
import io.karpfen.ide.runtime.host.RuntimeHostKind
import io.karpfen.ide.runtime.protocol.connection.ConnectionEnd
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.message.EngineHalt
import io.karpfen.ide.runtime.protocol.message.StateTiming
import io.karpfen.ide.runtime.protocol.message.TransitionTiming
import io.karpfen.ide.runtime.session.debug.PausePlace
import io.karpfen.ide.runtime.session.deploy.StepProgress
import io.karpfen.ide.runtime.session.deploy.StepStatus
import io.karpfen.ide.runtime.session.events.InjectionAvailability
import io.karpfen.ide.runtime.session.events.InjectionOutcome
import io.karpfen.ide.runtime.session.observation.ObservationMode
import io.karpfen.ide.runtime.session.observation.ObservedModel
import io.karpfen.ide.runtime.session.observation.SyncStatus
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.states.MachineActivity
import io.karpfen.ide.ui.workbench.settings.RuntimeSettings
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The words that the Runtime tool window and the status bar show for the runtime, the runs and their sessions. Pure.
 */
public object RuntimeTexts {

    /** How many characters of a payload the event log shows on one line. */
    public const val PAYLOAD_LINE_LIMIT: Int = 120

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /**
     * The host's [status] in words, prefixed by how [settings] reach the runtime, such as
     * `Docker: building the runtime image, Step 7/14` or `URL: http://127.0.0.1:8080 reachable`.
     */
    public fun hostStatus(settings: RuntimeSettings, status: HostStatus): String {
        val docker = settings.host == RuntimeHostKind.DOCKER
        val prefix = if (docker) "Docker" else "URL"
        val words = when (status) {
            HostStatus.Stopped -> if (docker) "not started" else "${settings.url} not connected"
            is HostStatus.Preparing -> listOf(stage(status.stage), status.detail).filter { it.isNotBlank() }.joinToString(", ")
            is HostStatus.Running ->
                if (docker) "running at ${status.endpoint.host}:${status.endpoint.port}" else "${status.endpoint.httpBase} reachable"
            is HostStatus.Failed -> hostProblem(status.problem)
            HostStatus.Stopping -> "stopping"
        }
        return "$prefix: $words"
    }

    /** The host's [status] in a few words for the status bar, such as `Runtime: running`. */
    public fun hostSummary(status: HostStatus): String = "Runtime: " + when (status) {
        HostStatus.Stopped -> "stopped"
        is HostStatus.Preparing -> if (status.stage == HostStage.BUILDING_IMAGE) "building image" else "starting"
        is HostStatus.Running -> "running"
        is HostStatus.Failed -> "failed"
        HostStatus.Stopping -> "stopping"
    }

    /** The message of [problem], followed by its hint if it has one. */
    public fun hostProblem(problem: HostProblem): String = listOfNotNull(problem.message, problem.hint).joinToString(" ")

    /** The label of [phase]. */
    public fun phase(phase: RunPhase): String = when (phase) {
        RunPhase.PREPARING -> "preparing"
        RunPhase.BLOCKED -> "blocked"
        RunPhase.WAITING_FOR_RUNTIME -> "waiting for the runtime"
        RunPhase.DEPLOYING -> "deploying"
        RunPhase.RUNNING -> "running"
        RunPhase.READY -> "ready"
        RunPhase.STOPPED -> "stopped"
        RunPhase.FAILED -> "failed"
    }

    /** A deploy [step] with its status and, if it has one, its detail, joined by a comma. */
    public fun step(step: StepProgress): String {
        val status = when (step.status) {
            StepStatus.PENDING -> "pending"
            StepStatus.RUNNING -> "running"
            StepStatus.DONE -> "done"
            StepStatus.SKIPPED -> "skipped"
            StepStatus.FAILED -> "failed"
        }
        return listOfNotNull("${step.title}: $status", step.detail).joinToString(", ")
    }

    /**
     * A deploy [problem] with the file it points to, relative to [root], and the one-based position in that file. The
     * position is only shown when the problem carries a range and a text snapshot to resolve it.
     */
    public fun problem(problem: DeployProblem, root: Path): String {
        val location = problem.location ?: return problem.message
        val file = runCatching { root.relativize(location.file) }.getOrDefault(location.file)
        val position = location.range?.let { range -> location.snapshot?.positionOf(range.start) }
        val where = position?.let { "$file:${it.line + 1}:${it.column + 1}" } ?: file.toString()
        return "$where: ${problem.message}"
    }

    /**
     * The active states of [machine]. The text starts with the machine's attachment key and then lists its state
     * stack as a path, for example `turtle: drive > drive fast`.
     */
    public fun activeStates(machine: MachineActivity): String = "${machine.attachedTo}: ${machine.stack.joinToString(" > ")}"

    /** The connection [state] in words. */
    public fun connection(state: ConnectionState): String = when (state) {
        ConnectionState.Idle -> "not connected"
        is ConnectionState.Connecting -> "connecting (attempt ${state.attempt})"
        is ConnectionState.Open -> "open"
        is ConnectionState.Reconnecting -> "reconnecting (attempt ${state.attempt})"
        is ConnectionState.Ended -> "ended: " + when (val end = state.end) {
            is ConnectionEnd.HandshakeRejected -> "the handshake was rejected"
            ConnectionEnd.EnvironmentStopped -> "the environment stopped"
            is ConnectionEnd.EnvironmentLost -> end.reason
            ConnectionEnd.Disconnected -> "disconnected"
            is ConnectionEnd.GaveUp -> "gave up after ${end.attempts} attempts (${end.lastFailure})"
        }
    }

    /** The observation switch of [model] in words. */
    public fun mode(model: ObservedModel): String = if (model.mode == ObservationMode.ON) "Observation on" else "Observation off"

    /** How far [model] is in step with the runtime, with its revision. */
    public fun sync(model: ObservedModel): String = when (val sync = model.sync) {
        SyncStatus.Predicted -> "predicted, nothing read yet"
        is SyncStatus.Pulled -> "pulled at revision ${sync.revision}"
        is SyncStatus.Synchronizing -> "synchronizing (${sync.reason.name.lowercase().replace('_', ' ')})"
        is SyncStatus.Live -> "live at revision ${sync.revision}"
        is SyncStatus.Final -> "final at revision ${sync.revision}"
    }

    /** Whether events can be sent now, as a sentence for the user. */
    public fun availability(availability: InjectionAvailability): String = when (availability) {
        InjectionAvailability.NotYet -> "Events cannot be sent yet, because the environment is not deployed."
        InjectionAvailability.Queued -> "The environment has not started yet. An event sent now waits until it starts."
        InjectionAvailability.Live -> "The environment is running. An event is delivered at once."
        is InjectionAvailability.Unavailable -> "Events cannot be sent: ${availability.reason}"
    }

    /** The outcome of a send as a sentence for the user. */
    public fun outcome(outcome: InjectionOutcome): String = when (outcome) {
        is InjectionOutcome.Sent -> "Sent. It shows in the list below once the runtime has published it."
        is InjectionOutcome.Refused ->
            "Not sent: ${outcome.validation.diagnostics.firstOrNull()?.message ?: "the payload has errors"}"
        is InjectionOutcome.Unavailable -> "Not sent. ${availability(outcome.availability)}"
    }

    /**
     * The deployment in [steps] in one line. It names the failed step if there is one, the step that runs or waits
     * while the deployment is under way, and the number of steps once all are through.
     */
    public fun deployment(steps: List<StepProgress>): String {
        val failed = steps.firstOrNull { it.status == StepStatus.FAILED }
        val open = steps.firstOrNull { it.status == StepStatus.RUNNING || it.status == StepStatus.PENDING }
        return when {
            steps.isEmpty() -> "Deployment: not started"
            failed != null -> "Deployment failed at: ${failed.title}"
            open != null -> "Deploying: ${open.title}"
            else -> "Deployment done, ${steps.size} steps"
        }
    }

    /**
     * When the event of [entry] was published, in local time. If the runtime left the time out, it is the time since
     * the launch instead.
     */
    public fun time(entry: DomainEventEntry): String =
        entry.event.event?.let { TIME.format(Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault())) }
            ?: "+${entry.event.elapsed}"

    /** The event name of [entry], or a hyphen if the runtime left it out. */
    public fun eventName(entry: DomainEventEntry): String = entry.event.event?.name ?: "-"

    /**
     * Who raised the event of [entry]. It is "internal" for a state machine and "external" for something outside the
     * environment. A hyphen stands for an event without a known source.
     */
    public fun source(entry: DomainEventEntry): String = when (entry.event.event?.source) {
        null -> "-"
        "INTERNAL" -> "internal"
        else -> "external"
    }

    /**
     * The payload of [entry] on one line. Line breaks and the blanks around them become one space. A longer payload is
     * cut to [PAYLOAD_LINE_LIMIT] characters, the last of which is an ellipsis.
     */
    public fun payloadLine(entry: DomainEventEntry): String {
        val line = payload(entry).replace(Regex("\\s*\\R\\s*"), " ")
        return if (line.length <= PAYLOAD_LINE_LIMIT) line else line.take(PAYLOAD_LINE_LIMIT - 1) + "…"
    }

    /** The whole payload of [entry]. */
    public fun payload(entry: DomainEventEntry): String = entry.event.event?.payload ?: entry.event.rawPayload

    /**
     * Where the debugger paused a run, as a line for the user, such as `Paused before the entry of cars wait`,
     * `Paused after the transition cars wait to cars go` or `Paused between two ticks`. The states of a split or a join
     * are joined by "and".
     */
    public fun pause(place: PausePlace): String = when (place) {
        PausePlace.BetweenTicks -> "Paused between two ticks"
        is PausePlace.AtBreakpoint -> when (val halt = place.halt) {
            is EngineHalt.AtState -> "Paused before the ${if (halt.timing == StateTiming.ENTRY) "entry" else "exit"} of ${halt.state}"
            is EngineHalt.AtTransition -> {
                val side = if (halt.timing == TransitionTiming.START) "before" else "after"
                "Paused $side the transition ${halt.from.joinToString(" and ")} to ${halt.to.joinToString(" and ")}"
            }
        }
    }

    /** The words for the stage of a host that prepares its runtime. */
    private fun stage(stage: HostStage): String = when (stage) {
        HostStage.CHECKING_ENGINE -> "checking the Docker engine"
        HostStage.BUILDING_IMAGE -> "building the runtime image"
        HostStage.CREATING_CONTAINER -> "creating the container"
        HostStage.STARTING -> "starting the container"
        HostStage.WAITING_FOR_HEALTH -> "waiting for the runtime"
    }
}
