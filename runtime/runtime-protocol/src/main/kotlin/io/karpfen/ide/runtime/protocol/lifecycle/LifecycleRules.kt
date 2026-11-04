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
package io.karpfen.ide.runtime.protocol.lifecycle

import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.protocol.message.RuntimePhase

/** Whether an operation may be sent in a phase. */
internal sealed interface Verdict {
    /** Send it. */
    data object Send : Verdict

    /** Answer it locally with success and send nothing: stopping a stopped environment. */
    data object AnswerLocally : Verdict

    /**
     * Refuse it and send nothing.
     *
     * @property reason why, in words.
     */
    data class Refuse(val reason: String) : Verdict
}

/** What follows a failed call. */
internal sealed interface OutcomeResolution {
    /**
     * The phase is known.
     *
     * @property phase the phase after the call.
     */
    data class Settled(val phase: EnvironmentPhase) : OutcomeResolution

    /** The phase must be asked for with a snapshot. */
    data object Probe : OutcomeResolution
}

/** What a probe, a snapshot read through the gateway after a call whose effect is unknown, found. */
internal sealed interface ProbeResult {
    /**
     * The runtime answered with a snapshot.
     *
     * @property snapshot the snapshot.
     */
    data class Reported(val snapshot: RuntimeModelSnapshot) : ProbeResult

    /**
     * The snapshot failed.
     *
     * @property failure why.
     */
    data class Failed(val failure: RuntimeFailure) : ProbeResult
}

/**
 * The environment lifecycle as pure functions: which operation may be sent in which phase, and which phase follows
 * each outcome. The environment handle asks [verdict] before every call and the other functions after it.
 *
 * Verdicts: configuring calls (uploads, the tick delay, the time to live, observers and listeners) only while
 * configuring, since the runtime freezes them at activation; the metamodel only before the event definitions and the
 * model, which are bound to its types, and those two only after it; access keys in every phase but stopped, uncertain and
 * lost; activation once, while configuring with a metamodel and a model; a start only of an activated environment (a
 * halted or stopped one is never restarted); a stop of an activated, running, halted or uncertain environment, answered
 * locally for a stopped one; a forced state while activated or running; reading the source or a snapshot unless lost;
 * subscribing while configuring, activated, running or halted; unsubscribing unless lost; injecting an event while
 * activated or running.
 *
 * Transitions: a success of a configuring call adds to the uploads, a success of activate, start and stop moves to
 * activated, running and stopped, a snapshot reports its phase, and other successes change nothing. A failure that
 * leaves open whether the call was applied (no answer, an internal error of the runtime, an unexpected status or body)
 * changes nothing, except for activate, start and stop, whose phase is then probed. A rejection is reconciled: an
 * unknown environment is lost, a rejection that shows the runtime's phase differs from the believed one is probed, any
 * other changes nothing; activate, start, stop and the snapshot have rules of their own. A probe's snapshot settles the
 * phase; a probe that fails leaves the phase uncertain between the phases the operation could have left.
 *
 * Stateless.
 */
internal object LifecycleRules {

    /** Whether [operation] may be sent in [phase]. */
    fun verdict(phase: EnvironmentPhase, operation: LifecycleOperation): Verdict =
        when (phase) {
            is EnvironmentPhase.Configuring -> configuring(phase.uploads, operation)
            EnvironmentPhase.Activated -> activated(operation)
            EnvironmentPhase.Running -> running(operation)
            EnvironmentPhase.Halted -> halted(operation)
            EnvironmentPhase.Stopped -> stopped(operation)
            is EnvironmentPhase.Uncertain -> uncertain(operation)
            is EnvironmentPhase.Lost -> Verdict.Refuse("the runtime no longer knows the environment (${phase.reason})")
        }

    /** The phase after a success of [operation] in [phase]; [attachedTo] is the machine a state machine upload attached. */
    fun afterSuccess(phase: EnvironmentPhase, operation: LifecycleOperation, attachedTo: String? = null): EnvironmentPhase =
        when (operation) {
            LifecycleOperation.ACTIVATE -> EnvironmentPhase.Activated
            LifecycleOperation.START -> EnvironmentPhase.Running
            LifecycleOperation.STOP -> EnvironmentPhase.Stopped
            else -> if (phase is EnvironmentPhase.Configuring) {
                EnvironmentPhase.Configuring(phase.uploads.after(operation, attachedTo))
            } else {
                phase
            }
        }

    /** What follows a [failure] of [operation] in [phase]. */
    fun afterFailure(phase: EnvironmentPhase, operation: LifecycleOperation, failure: RuntimeFailure): OutcomeResolution {
        val rejection = failure as? RuntimeFailure.Rejected
        return when (operation) {
            LifecycleOperation.ACTIVATE -> when (rejection?.kind) {
                null -> unknownOutcome(phase, failure, probes = true)
                RejectionKind.ALREADY_ACTIVE -> OutcomeResolution.Probe
                RejectionKind.UNKNOWN_ENVIRONMENT -> lost(rejection)
                else -> OutcomeResolution.Settled(phase)
            }
            LifecycleOperation.START, LifecycleOperation.STOP -> when (rejection?.kind) {
                null -> unknownOutcome(phase, failure, probes = true)
                RejectionKind.NOT_ACTIVATED -> OutcomeResolution.Probe
                else -> reconcile(phase, rejection)
            }
            LifecycleOperation.READ_SNAPSHOT -> when (rejection?.kind) {
                null -> unknownOutcome(phase, failure, probes = false)
                RejectionKind.NO_MODEL, RejectionKind.REFERENCE_LOOP -> OutcomeResolution.Settled(phase)
                RejectionKind.TICK_BOUNDARY_TIMEOUT -> OutcomeResolution.Settled(EnvironmentPhase.Running)
                else -> reconcile(phase, rejection)
            }
            else -> if (rejection == null) unknownOutcome(phase, failure, probes = false) else reconcile(phase, rejection)
        }
    }

    /** The phase after a probe for [operation] in [phase] found [probe]. */
    fun afterProbe(phase: EnvironmentPhase, operation: LifecycleOperation, probe: ProbeResult): EnvironmentPhase =
        when (probe) {
            is ProbeResult.Reported -> afterSnapshot(phase, probe.snapshot)
            is ProbeResult.Failed -> when ((probe.failure as? RuntimeFailure.Rejected)?.kind) {
                RejectionKind.NO_MODEL -> EnvironmentPhase.Configuring(phase.knownUploads())
                RejectionKind.TICK_BOUNDARY_TIMEOUT -> EnvironmentPhase.Running
                RejectionKind.UNKNOWN_ENVIRONMENT -> EnvironmentPhase.Lost(probe.failure.describe())
                else -> EnvironmentPhase.Uncertain(
                    candidates(phase, operation),
                    "${operation.words()} may or may not have been applied, and the snapshot that should tell failed: " +
                        probe.failure.describe(),
                    phase.knownUploads(),
                )
            }
        }

    /** The phase that [snapshot] reports, keeping the known uploads of [phase] for a configuring environment. */
    fun afterSnapshot(phase: EnvironmentPhase, snapshot: RuntimeModelSnapshot): EnvironmentPhase =
        when (snapshot.phase) {
            RuntimePhase.CONFIGURING -> EnvironmentPhase.Configuring(phase.knownUploads())
            RuntimePhase.ACTIVATED -> EnvironmentPhase.Activated
            RuntimePhase.RUNNING -> EnvironmentPhase.Running
            RuntimePhase.HALTED -> EnvironmentPhase.Halted
            RuntimePhase.STOPPED -> EnvironmentPhase.Stopped
        }

    /** The kind of [phase]; null for the phases that are not one of the runtime's. */
    fun kindOf(phase: EnvironmentPhase): PhaseKind? =
        when (phase) {
            is EnvironmentPhase.Configuring -> PhaseKind.CONFIGURING
            EnvironmentPhase.Activated -> PhaseKind.ACTIVATED
            EnvironmentPhase.Running -> PhaseKind.RUNNING
            EnvironmentPhase.Halted -> PhaseKind.HALTED
            EnvironmentPhase.Stopped -> PhaseKind.STOPPED
            is EnvironmentPhase.Uncertain, is EnvironmentPhase.Lost -> null
        }

    /** The verdicts while configuring with [uploads]. */
    private fun configuring(uploads: Uploads, operation: LifecycleOperation): Verdict =
        when (operation) {
            LifecycleOperation.SET_METAMODEL -> if (uploads.events || uploads.model) {
                Verdict.Refuse("the event definitions or the model were uploaded already, and they are bound to the metamodel's types")
            } else {
                Verdict.Send
            }
            LifecycleOperation.SET_EVENT_DEFINITIONS, LifecycleOperation.SET_MODEL -> if (uploads.metamodel) {
                Verdict.Send
            } else {
                Verdict.Refuse("the metamodel must be uploaded first")
            }
            LifecycleOperation.SET_STATE_MACHINE, LifecycleOperation.SET_TICK_DELAY, LifecycleOperation.SET_EVENT_TTL,
            LifecycleOperation.REGISTER_OBJECT_OBSERVER, LifecycleOperation.REGISTER_DOMAIN_LISTENER,
            LifecycleOperation.ISSUE_ACCESS_KEY, LifecycleOperation.READ_STATE_MACHINE_SOURCE,
            LifecycleOperation.READ_SNAPSHOT, LifecycleOperation.SUBSCRIBE_MODEL, LifecycleOperation.UNSUBSCRIBE_MODEL,
            -> Verdict.Send
            LifecycleOperation.ACTIVATE -> if (uploads.metamodel && uploads.model) {
                Verdict.Send
            } else {
                Verdict.Refuse("the metamodel and the model must be uploaded before activation")
            }
            LifecycleOperation.START -> Verdict.Refuse("the environment must be activated before it is started")
            LifecycleOperation.STOP -> Verdict.Refuse("nothing runs yet in a configuring environment")
            LifecycleOperation.FORCE_STATE -> Verdict.Refuse("a state can only be forced in an activated or running environment")
            LifecycleOperation.INJECT_EVENT -> Verdict.Refuse("the runtime drops events sent before activation")
        }

    /** The verdicts while activated. */
    private fun activated(operation: LifecycleOperation): Verdict =
        when (operation) {
            LifecycleOperation.ACTIVATE -> Verdict.Refuse("the environment is activated already")
            LifecycleOperation.ISSUE_ACCESS_KEY, LifecycleOperation.START, LifecycleOperation.STOP,
            LifecycleOperation.FORCE_STATE, LifecycleOperation.READ_STATE_MACHINE_SOURCE, LifecycleOperation.READ_SNAPSHOT,
            LifecycleOperation.SUBSCRIBE_MODEL, LifecycleOperation.UNSUBSCRIBE_MODEL, LifecycleOperation.INJECT_EVENT,
            -> Verdict.Send
            else -> frozen()
        }

    /** The verdicts while running. */
    private fun running(operation: LifecycleOperation): Verdict =
        when (operation) {
            LifecycleOperation.ACTIVATE -> Verdict.Refuse("the environment is activated already")
            LifecycleOperation.START -> Verdict.Refuse("the environment runs already")
            LifecycleOperation.ISSUE_ACCESS_KEY, LifecycleOperation.STOP, LifecycleOperation.FORCE_STATE,
            LifecycleOperation.READ_STATE_MACHINE_SOURCE, LifecycleOperation.READ_SNAPSHOT,
            LifecycleOperation.SUBSCRIBE_MODEL, LifecycleOperation.UNSUBSCRIBE_MODEL, LifecycleOperation.INJECT_EVENT,
            -> Verdict.Send
            else -> frozen()
        }

    /** The verdicts while halted. */
    private fun halted(operation: LifecycleOperation): Verdict =
        when (operation) {
            LifecycleOperation.ACTIVATE -> Verdict.Refuse("the environment is activated already")
            LifecycleOperation.START -> Verdict.Refuse("the engine ended on its own, and a halted environment is not restarted")
            LifecycleOperation.FORCE_STATE -> Verdict.Refuse("the engine ended on its own, so a forced state would never apply")
            LifecycleOperation.INJECT_EVENT -> Verdict.Refuse("the engine ended on its own, so no event would be handled")
            LifecycleOperation.ISSUE_ACCESS_KEY, LifecycleOperation.STOP, LifecycleOperation.READ_STATE_MACHINE_SOURCE,
            LifecycleOperation.READ_SNAPSHOT, LifecycleOperation.SUBSCRIBE_MODEL, LifecycleOperation.UNSUBSCRIBE_MODEL,
            -> Verdict.Send
            else -> frozen()
        }

    /** The verdicts while stopped. */
    private fun stopped(operation: LifecycleOperation): Verdict =
        when (operation) {
            LifecycleOperation.STOP -> Verdict.AnswerLocally
            LifecycleOperation.READ_STATE_MACHINE_SOURCE, LifecycleOperation.READ_SNAPSHOT,
            LifecycleOperation.UNSUBSCRIBE_MODEL,
            -> Verdict.Send
            LifecycleOperation.ISSUE_ACCESS_KEY, LifecycleOperation.SUBSCRIBE_MODEL ->
                Verdict.Refuse("the runtime closed the environment's sockets when it stopped")
            else -> Verdict.Refuse("a stopped environment is final; running again deploys a fresh environment")
        }

    /** The verdicts while the phase is uncertain. */
    private fun uncertain(operation: LifecycleOperation): Verdict =
        when (operation) {
            LifecycleOperation.STOP, LifecycleOperation.READ_STATE_MACHINE_SOURCE, LifecycleOperation.READ_SNAPSHOT,
            LifecycleOperation.UNSUBSCRIBE_MODEL,
            -> Verdict.Send
            else -> Verdict.Refuse("the environment's phase is uncertain; a snapshot resolves it")
        }

    /** The refusal of a configuring call after activation. */
    private fun frozen(): Verdict = Verdict.Refuse("the configuration is frozen at activation")

    /** The resolution of a [failure] that leaves open whether the call was applied; [probes] for activate, start and stop. */
    private fun unknownOutcome(phase: EnvironmentPhase, failure: RuntimeFailure, probes: Boolean): OutcomeResolution =
        if (probes && failure !is RuntimeFailure.Unreachable) OutcomeResolution.Probe else OutcomeResolution.Settled(phase)

    /**
     * The resolution of a [rejection] by the general rule: an unknown environment is lost, a rejection that shows the
     * runtime's phase differs from the believed one is probed, every other leaves the phase unchanged.
     */
    private fun reconcile(phase: EnvironmentPhase, rejection: RuntimeFailure.Rejected): OutcomeResolution =
        when (rejection.kind) {
            RejectionKind.UNKNOWN_ENVIRONMENT -> lost(rejection)
            RejectionKind.ALREADY_ACTIVE, RejectionKind.NOT_ACTIVE, RejectionKind.NO_EXECUTION_THREAD -> OutcomeResolution.Probe
            else -> OutcomeResolution.Settled(phase)
        }

    /** The environment is lost, as [rejection] shows. */
    private fun lost(rejection: RuntimeFailure.Rejected): OutcomeResolution =
        OutcomeResolution.Settled(EnvironmentPhase.Lost(rejection.describe()))

    /**
     * The phases [operation] could have left, believed to be in [phase]: activation configuring or activated, a start
     * activated, running or halted, a stop the believed phase or stopped; for any other operation, whose rejection showed
     * that the runtime's phase differs from the believed one, every phase but the believed one.
     */
    private fun candidates(phase: EnvironmentPhase, operation: LifecycleOperation): Set<PhaseKind> {
        val believed = believedKinds(phase)
        return when (operation) {
            LifecycleOperation.ACTIVATE -> setOf(PhaseKind.CONFIGURING, PhaseKind.ACTIVATED)
            LifecycleOperation.START -> setOf(PhaseKind.ACTIVATED, PhaseKind.RUNNING, PhaseKind.HALTED)
            LifecycleOperation.STOP -> believed + PhaseKind.STOPPED
            else -> PhaseKind.entries.toSet() - believed
        }
    }

    /** The phases [phase] stands for: its own kind, or the candidates of an uncertain phase. */
    private fun believedKinds(phase: EnvironmentPhase): Set<PhaseKind> =
        when (phase) {
            is EnvironmentPhase.Uncertain -> phase.candidates
            else -> setOfNotNull(kindOf(phase))
        }

    /** The uploads the client knows of in this phase: those of a configuring or uncertain phase, else none. */
    private fun EnvironmentPhase.knownUploads(): Uploads =
        when (this) {
            is EnvironmentPhase.Configuring -> uploads
            is EnvironmentPhase.Uncertain -> uploads
            else -> Uploads.NONE
        }

    /** These uploads after a success of [operation], which attached [attachedTo] for a state machine. */
    private fun Uploads.after(operation: LifecycleOperation, attachedTo: String?): Uploads =
        when (operation) {
            LifecycleOperation.SET_METAMODEL -> copy(metamodel = true)
            LifecycleOperation.SET_EVENT_DEFINITIONS -> copy(events = true)
            LifecycleOperation.SET_MODEL -> copy(model = true)
            LifecycleOperation.SET_STATE_MACHINE -> copy(machines = machines + listOfNotNull(attachedTo))
            LifecycleOperation.SET_TICK_DELAY -> copy(tickDelay = true)
            LifecycleOperation.SET_EVENT_TTL -> copy(eventTtl = true)
            LifecycleOperation.REGISTER_OBJECT_OBSERVER -> copy(observers = observers + 1)
            LifecycleOperation.REGISTER_DOMAIN_LISTENER -> copy(listeners = listeners + 1)
            else -> this
        }

    /** The operation as a sentence names it. */
    private fun LifecycleOperation.words(): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
