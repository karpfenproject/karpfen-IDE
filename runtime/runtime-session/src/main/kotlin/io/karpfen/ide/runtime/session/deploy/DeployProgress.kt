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

import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey

/**
 * The steps of a deployment, in the order they run. Some steps run once per item, such as one attachment per machine.
 */
public enum class DeployStepKind {
    /** Checks that the runtime is up and has the IDE's routes, before anything is created. */
    CHECK_RUNTIME,

    /** Creates the environment. */
    CREATE,

    /** Connects to the environment's WebSocket and sends the handshake. */
    CONNECT,

    /** Uploads the domain metamodel. */
    UPLOAD_METAMODEL,

    /** Uploads the event definitions. It is skipped when the context has no events file. */
    UPLOAD_EVENTS,

    /** Uploads the model. */
    UPLOAD_MODEL,

    /** Attaches one state machine to its object key. */
    ATTACH_STATE_MACHINE,

    /** Sets the tick delay of the deploy configuration. */
    SET_TICK_DELAY,

    /** Sets the event time to live of the deploy configuration. */
    SET_EVENT_TTL,

    /** Registers a listener for every event domain the state machines use. It is skipped when there are none. */
    LISTEN_DOMAINS,

    /**
     * Switches on the runtime features the state machines need, before the activation. It is skipped when they need
     * none.
     */
    SWITCH_FEATURES_ON,

    /** Activates the environment. */
    ACTIVATE,

    /** Switches observation on, or reads the model back once. */
    OBSERVE,

    /** Starts the environment. It is skipped when the deployment does not start it. */
    START,

    /**
     * Waits until every state machine reports an active state. It is skipped when the deployment does not start the
     * environment.
     */
    CONFIRM_START,
}

/** Where a step of a deployment stands. */
public enum class StepStatus {
    /** Not reached yet. */
    PENDING,

    /** Running now. */
    RUNNING,

    /** Done. */
    DONE,

    /** Not needed for this deployment. */
    SKIPPED,

    /** Failed or cancelled. The deployment stopped here. */
    FAILED,
}

/**
 * One step of a deployment as the user sees it. Immutable.
 *
 * @property kind what the step does.
 * @property title what it concerns, in words, such as "Uploading lamp.kmodel".
 * @property status where it stands.
 * @property detail what it is doing or did, such as "2 of 3: switch", or null.
 */
public data class StepProgress(
    public val kind: DeployStepKind,
    public val title: String,
    public val status: StepStatus,
    public val detail: String?,
)

/**
 * The progress of a deployment, with every step and its status. Immutable.
 *
 * @property steps the steps in the order they run.
 * @property environment the key of the environment once it was created, otherwise null.
 */
public data class DeployProgress(
    public val steps: List<StepProgress>,
    public val environment: EnvironmentKey?,
) {
    /**
     * The step running now or the one that failed. It is null before the first step starts and after the last one
     * ended.
     */
    public val current: StepProgress?
        get() = steps.firstOrNull { it.status == StepStatus.RUNNING || it.status == StepStatus.FAILED }

    /** The number of steps that are done or skipped. */
    public val completed: Int
        get() = steps.count { it.status == StepStatus.DONE || it.status == StepStatus.SKIPPED }

    /** The number of steps. */
    public val total: Int
        get() = steps.size
}
