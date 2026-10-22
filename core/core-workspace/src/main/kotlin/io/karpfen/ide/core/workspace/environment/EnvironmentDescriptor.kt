package io.karpfen.ide.core.workspace.environment

import io.karpfen.ide.core.document.environment.EnvironmentSchema
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An environment descriptor `*.kenv.json` as a typed value: which files form one runnable modeling context and how the
 * runtime runs them. File paths are relative to the descriptor's directory.
 *
 * The keys, their nesting, their value kinds and which of them are required are defined once, by `EnvironmentSchema`
 * of core-document, which also documents every key. This class is what kotlinx.serialization decodes a descriptor into
 * after the reader checked it against that schema: its serial names are the schema's key constants, and a test checks
 * that its serial shape (keys, nesting, optional and nullable values, value kinds) is exactly the schema's. It is also
 * what the file templates encode a new descriptor from, so a written descriptor reads back without findings.
 *
 * @property metamodel the domain metamodel (required).
 * @property events the event metamodel, if any.
 * @property model the model (required).
 * @property stateMachines state machines and the object key each one is attached to.
 * @property tickDelayMs runtime tick delay in milliseconds.
 * @property eventTtlMs event time-to-live in milliseconds.
 * @property observe what the IDE observes while running.
 * @property runtime runtime image and extra Python packages.
 * @property bridges bridge configuration files (`*.kbridge.json`), not interpreted by the IDE yet.
 */
@Serializable
public data class EnvironmentDescriptor(
    @SerialName(EnvironmentSchema.METAMODEL) public val metamodel: String,
    @SerialName(EnvironmentSchema.EVENTS) public val events: String? = null,
    @SerialName(EnvironmentSchema.MODEL) public val model: String,
    @SerialName(EnvironmentSchema.STATE_MACHINES) public val stateMachines: List<StateMachineEntry> = emptyList(),
    @SerialName(EnvironmentSchema.TICK_DELAY_MS) public val tickDelayMs: Long? = null,
    @SerialName(EnvironmentSchema.EVENT_TTL_MS) public val eventTtlMs: Long? = null,
    @SerialName(EnvironmentSchema.OBSERVE) public val observe: ObserveSpec? = null,
    @SerialName(EnvironmentSchema.RUNTIME) public val runtime: RuntimeSpec? = null,
    @SerialName(EnvironmentSchema.BRIDGES) public val bridges: List<String> = emptyList(),
) {
    /** One state machine of the environment: [file] runs attached to the object with key [attachedTo]. */
    @Serializable
    public data class StateMachineEntry(
        @SerialName(EnvironmentSchema.FILE) public val file: String,
        @SerialName(EnvironmentSchema.ATTACHED_TO) public val attachedTo: String,
    )

    /** Observation settings: [objects] is an object key pattern (`"*"` for all), [domains] the event domains. */
    @Serializable
    public data class ObserveSpec(
        @SerialName(EnvironmentSchema.OBJECTS) public val objects: String,
        @SerialName(EnvironmentSchema.DOMAINS) public val domains: List<String> = emptyList(),
    )

    /** Runtime settings: container [image] and extra [pythonPackages]. */
    @Serializable
    public data class RuntimeSpec(
        @SerialName(EnvironmentSchema.IMAGE) public val image: String? = null,
        @SerialName(EnvironmentSchema.PYTHON_PACKAGES) public val pythonPackages: List<String> = emptyList(),
    )
}
