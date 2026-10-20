package io.karpfen.ide.core.workspace.environment

import kotlinx.serialization.Serializable

/**
 * An environment descriptor `*.kenv.json` (architecture proposal §5.2, spec §4): which files form one runnable
 * modeling context and how the runtime runs them. File paths are relative to the descriptor's directory.
 *
 * ```json
 * {
 *   "metamodel": "cleaning_robot.kmeta",
 *   "events": "EVENTS.kmeta",
 *   "model": "cleaning_robot.kmodel",
 *   "stateMachines": [ { "file": "cleaning_robot.kstates", "attachedTo": "turtle" } ],
 *   "tickDelayMs": 50, "eventTtlMs": 3000,
 *   "observe": { "objects": "*", "domains": ["public"] },
 *   "runtime": { "image": "ghcr.io/karpfenproject/karpfen-runtime:0.x", "pythonPackages": ["numpy"] },
 *   "bridges": [ "mqtt-sim.kbridge.json" ]
 * }
 * ```
 *
 * @property metamodel the domain metamodel (required).
 * @property events the event metamodel, if any.
 * @property model the model (required).
 * @property stateMachines state machines and the object key each one is attached to.
 * @property tickDelayMs runtime tick delay in milliseconds.
 * @property eventTtlMs event time-to-live in milliseconds.
 * @property observe what the IDE observes while running.
 * @property runtime runtime image and extra Python packages.
 * @property bridges bridge configuration files (`*.kbridge.json`), not interpreted in phase 1.
 */
@Serializable
public data class EnvironmentDescriptor(
    public val metamodel: String,
    public val events: String? = null,
    public val model: String,
    public val stateMachines: List<StateMachineEntry> = emptyList(),
    public val tickDelayMs: Long? = null,
    public val eventTtlMs: Long? = null,
    public val observe: ObserveSpec? = null,
    public val runtime: RuntimeSpec? = null,
    public val bridges: List<String> = emptyList(),
) {
    /** One state machine of the environment: [file] runs attached to the object with key [attachedTo]. */
    @Serializable
    public data class StateMachineEntry(public val file: String, public val attachedTo: String)

    /** Observation settings: [objects] is an object key pattern (`"*"` for all), [domains] the event domains. */
    @Serializable
    public data class ObserveSpec(public val objects: String, public val domains: List<String> = emptyList())

    /** Runtime settings: container [image] and extra [pythonPackages]. */
    @Serializable
    public data class RuntimeSpec(public val image: String? = null, public val pythonPackages: List<String> = emptyList())
}
