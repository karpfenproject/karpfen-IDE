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
package io.karpfen.ide.runtime.session.problem

/**
 * The stable codes of [DeployProblem], kebab-case like the workspace's diagnostic codes. Each constant states the
 * severity its problems have.
 */
public object DeployProblemCodes {
    /**
     * ERROR: the descriptor resolves to no explicit context; it could not be read or decoded, and its own problems say
     * why.
     */
    public const val NO_CONTEXT: String = "deploy-no-context"

    /** ERROR: saving an open document before the deploy failed, with the file's message. */
    public const val SAVE_FAILED: String = "deploy-save-failed"

    /**
     * ERROR: a file of the context or the descriptor still differs from its saved state after saving, because it was
     * edited during the preparation; deploying again resolves it.
     */
    public const val UNSAVED_FILE: String = "deploy-unsaved-file"

    /**
     * ERROR: a file of the context or the descriptor has an ERROR in the analyzed text that would be uploaded; one
     * problem per file, at its first error, with the count.
     */
    public const val CONTEXT_ERRORS: String = "deploy-context-errors"

    /** ERROR: the context names no domain metamodel, or it was not read. */
    public const val NO_METAMODEL: String = "deploy-no-metamodel"

    /** ERROR: the context names no model, or it was not read. */
    public const val NO_MODEL: String = "deploy-no-model"

    /** ERROR: a state machine is attached to a key no object of the model has; the runtime refuses the activation. */
    public const val UNKNOWN_ATTACHMENT: String = "deploy-unknown-attachment"

    /** ERROR: two state machines are attached to the same key; the runtime would keep the last silently. */
    public const val DUPLICATE_ATTACHMENT: String = "deploy-duplicate-attachment"

    /** WARNING: the attached object's type differs from the type after `ATTACHED TO`, which the runtime ignores. */
    public const val ATTACHMENT_TYPE: String = "deploy-attachment-type"

    /** ERROR: the tick delay is below 0 or above the largest `Int`, the type the runtime parses it as. */
    public const val TICK_DELAY_RANGE: String = "deploy-tick-delay-range"

    /** WARNING: the tick delay is 0, so the engine never sleeps. */
    public const val TICK_DELAY_ZERO: String = "deploy-tick-delay-zero"

    /** INFO for a time to live of 0, WARNING for a negative one, which behaves like 0: events never expire. */
    public const val EVENT_TTL_NEVER_EXPIRES: String = "deploy-event-ttl-never-expires"

    /**
     * ERROR: an object is reachable from itself through `has` and `knows` relations; the runtime writes `knows` targets
     * as nested objects, so it cannot write such a model.
     */
    public const val REFERENCE_LOOP: String = "deploy-reference-loop"

    /** WARNING: two objects share a key; the runtime keeps the last, and the mirror cannot tell them apart. */
    public const val DUPLICATE_OBJECT_KEY: String = "deploy-duplicate-object-key"

    /**
     * WARNING: an event domain is named like an attached object key, so forcing that machine's state discards the
     * domain's queued events.
     */
    public const val DOMAIN_IS_ATTACHMENT: String = "deploy-domain-is-attachment"

    /**
     * INFO: the descriptor's `observe` key is not used; the IDE observes the whole model while observation is on and
     * listens to every domain the state machines use.
     */
    public const val OBSERVE_SUPERSEDED: String = "deploy-observe-superseded"

    /** INFO: the runtime's image or Python packages, or bridges, are set; they concern hosting and bridges, built later. */
    public const val HOSTING_IGNORED: String = "deploy-hosting-ignored"

    /** ERROR: the runtime could not be reached, with its URL. */
    public const val RUNTIME_UNREACHABLE: String = "runtime-unreachable"

    /**
     * ERROR: the runtime lacks the IDE's routes, or answered with a status or a body that is not the runtime's.
     */
    public const val RUNTIME_INCOMPATIBLE: String = "runtime-incompatible"

    /** ERROR: the runtime did not answer in time, or the connection broke during a call. */
    public const val RUNTIME_NO_ANSWER: String = "runtime-no-answer"

    /** ERROR: the runtime refused a call with 400 or 409 and its text. */
    public const val RUNTIME_REJECTED: String = "runtime-rejected"

    /** ERROR: the runtime failed with an internal error (500). */
    public const val RUNTIME_ERROR: String = "runtime-error"

    /** ERROR: the runtime rejected the WebSocket handshake, with the reason. */
    public const val HANDSHAKE_REJECTED: String = "handshake-rejected"

    /**
     * ERROR when it fails a deploy step, WARNING afterwards: the connection could not be opened or gave up
     * reconnecting.
     */
    public const val CONNECTION_LOST: String = "connection-lost"

    /**
     * WARNING: the model the runtime read back differs from the model the IDE analyzed; one problem per object, at its
     * declaration.
     */
    public const val MODEL_DIFFERS: String = "deploy-model-differs"

    /** ERROR: the engine ended on its own; the runtime's console log has the reason. */
    public const val ENGINE_HALTED: String = "engine-halted"

    /** WARNING: after the start, not every state machine reported an active state within the confirmation bound. */
    public const val ENGINE_SILENT: String = "engine-silent"

    /**
     * WARNING: observation is on, but no snapshot could be read to re-synchronize; the mirror stays as it was until the
     * next pull.
     */
    public const val OBSERVATION_OUT_OF_SYNC: String = "observation-out-of-sync"

    /** INFO: an environment stays on the runtime, which cannot delete environments, with its key and phase. */
    public const val ENVIRONMENT_LEFT_BEHIND: String = "environment-left-behind"

    /** ERROR: the runtime no longer knows the environment, because it restarted. */
    public const val ENVIRONMENT_LOST: String = "environment-lost"

    /** Every code, in the order the constants are declared. */
    public val ALL: List<String> = listOf(
        NO_CONTEXT, SAVE_FAILED, UNSAVED_FILE, CONTEXT_ERRORS, NO_METAMODEL, NO_MODEL, UNKNOWN_ATTACHMENT,
        DUPLICATE_ATTACHMENT, ATTACHMENT_TYPE, TICK_DELAY_RANGE, TICK_DELAY_ZERO, EVENT_TTL_NEVER_EXPIRES, REFERENCE_LOOP,
        DUPLICATE_OBJECT_KEY, DOMAIN_IS_ATTACHMENT, OBSERVE_SUPERSEDED, HOSTING_IGNORED, RUNTIME_UNREACHABLE,
        RUNTIME_INCOMPATIBLE, RUNTIME_NO_ANSWER, RUNTIME_REJECTED, RUNTIME_ERROR, HANDSHAKE_REJECTED, CONNECTION_LOST,
        MODEL_DIFFERS, ENGINE_HALTED, ENGINE_SILENT, OBSERVATION_OUT_OF_SYNC, ENVIRONMENT_LEFT_BEHIND, ENVIRONMENT_LOST,
    )
}
