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
 * The stable codes of [DeployProblem], in kebab-case like the workspace's diagnostic codes. The KDoc of each constant
 * states the severity its problems have.
 */
public object DeployProblemCodes {
    /**
     * The descriptor resolves to no explicit context. It could not be read or decoded, and its own problems say why.
     * This is an error.
     */
    public const val NO_CONTEXT: String = "deploy-no-context"

    /**
     * Saving an open document before the deployment failed. The problem carries the file's message. This is an error.
     */
    public const val SAVE_FAILED: String = "deploy-save-failed"

    /**
     * A file of the context or the descriptor still differs from its saved state after saving, because it was edited
     * during the preparation. Deploying again resolves it. This is an error.
     */
    public const val UNSAVED_FILE: String = "deploy-unsaved-file"

    /**
     * A file of the context or the descriptor has an error in the analyzed text that would be uploaded. There is one
     * problem per file, at its first error, with the error count. This is an error.
     */
    public const val CONTEXT_ERRORS: String = "deploy-context-errors"

    /** The context names no domain metamodel, or the metamodel was not read. This is an error. */
    public const val NO_METAMODEL: String = "deploy-no-metamodel"

    /** The context names no model, or the model was not read. This is an error. */
    public const val NO_MODEL: String = "deploy-no-model"

    /**
     * A state machine is attached to a key that no object of the model has. The runtime refuses the activation. This
     * is an error.
     */
    public const val UNKNOWN_ATTACHMENT: String = "deploy-unknown-attachment"

    /**
     * Two state machines are attached to the same key. The runtime would silently keep the last one. This is an error.
     */
    public const val DUPLICATE_ATTACHMENT: String = "deploy-duplicate-attachment"

    /**
     * The attached object's type differs from the type after `ATTACHED TO`, which the runtime ignores. This is a
     * warning.
     */
    public const val ATTACHMENT_TYPE: String = "deploy-attachment-type"

    /**
     * The tick delay is below 0 or above the largest `Int`. The runtime parses the tick delay as an `Int`. This is an
     * error.
     */
    public const val TICK_DELAY_RANGE: String = "deploy-tick-delay-range"

    /** The tick delay is 0, so the engine never sleeps. This is a warning. */
    public const val TICK_DELAY_ZERO: String = "deploy-tick-delay-zero"

    /**
     * Events never expire. A time to live of 0 is an info. A negative time to live behaves like 0 and is a warning.
     */
    public const val EVENT_TTL_NEVER_EXPIRES: String = "deploy-event-ttl-never-expires"

    /**
     * An object is reachable from itself through `has` and `knows` relations. The runtime writes `knows` targets as
     * nested objects, so it cannot write such a model. This is an error.
     */
    public const val REFERENCE_LOOP: String = "deploy-reference-loop"

    /**
     * An event domain is named like an attached object key, so forcing that machine's state discards the domain's
     * queued events. This is a warning.
     */
    public const val DOMAIN_IS_ATTACHMENT: String = "deploy-domain-is-attachment"

    /**
     * The descriptor's `observe` key is not used. The IDE observes the whole model while observation is on, and it
     * listens to every domain the state machines use. This is an info.
     */
    public const val OBSERVE_SUPERSEDED: String = "deploy-observe-superseded"

    /**
     * The descriptor sets the runtime's image, its Python packages or bridges. They concern hosting the runtime and
     * bridges, which the deployment does not do. This is an info.
     */
    public const val HOSTING_IGNORED: String = "deploy-hosting-ignored"

    /** The runtime could not be reached. The problem names its URL. This is an error. */
    public const val RUNTIME_UNREACHABLE: String = "runtime-unreachable"

    /**
     * The runtime lacks the IDE's routes, or it answered with a status or a body that is not the runtime's. This is an
     * error.
     */
    public const val RUNTIME_INCOMPATIBLE: String = "runtime-incompatible"

    /** The runtime did not answer in time, or the connection broke during a call. This is an error. */
    public const val RUNTIME_NO_ANSWER: String = "runtime-no-answer"

    /** The runtime refused a call with 400 or 409 and its text. This is an error. */
    public const val RUNTIME_REJECTED: String = "runtime-rejected"

    /** The runtime failed with an internal error, status 500. This is an error. */
    public const val RUNTIME_ERROR: String = "runtime-error"

    /** The runtime rejected the WebSocket handshake. The problem gives the reason. This is an error. */
    public const val HANDSHAKE_REJECTED: String = "handshake-rejected"

    /**
     * The connection could not be opened, or it gave up reconnecting. This is an error when it fails a deploy step,
     * and a warning after the deployment.
     */
    public const val CONNECTION_LOST: String = "connection-lost"

    /**
     * The model the runtime read back differs from the model the IDE analyzed. There is one problem per object, at its
     * declaration. This is a warning.
     */
    public const val MODEL_DIFFERS: String = "deploy-model-differs"

    /** The engine ended on its own. The runtime's console log has the reason. This is an error. */
    public const val ENGINE_HALTED: String = "engine-halted"

    /**
     * After the start, not every state machine reported an active state within the confirmation bound. This is a
     * warning.
     */
    public const val ENGINE_SILENT: String = "engine-silent"

    /**
     * Observation is on, but no snapshot could be read to re-synchronize. The mirror stays as it was until the next
     * pull. This is a warning.
     */
    public const val OBSERVATION_OUT_OF_SYNC: String = "observation-out-of-sync"

    /**
     * An environment stays on the runtime, because the runtime cannot delete environments. The problem names its key
     * and phase. This is an info.
     */
    public const val ENVIRONMENT_LEFT_BEHIND: String = "environment-left-behind"

    /** The runtime no longer knows the environment, usually because the runtime restarted. This is an error. */
    public const val ENVIRONMENT_LOST: String = "environment-lost"

    /** Every code, in the order the constants are declared. */
    public val ALL: List<String> = listOf(
        NO_CONTEXT, SAVE_FAILED, UNSAVED_FILE, CONTEXT_ERRORS, NO_METAMODEL, NO_MODEL, UNKNOWN_ATTACHMENT,
        DUPLICATE_ATTACHMENT, ATTACHMENT_TYPE, TICK_DELAY_RANGE, TICK_DELAY_ZERO, EVENT_TTL_NEVER_EXPIRES, REFERENCE_LOOP,
        DOMAIN_IS_ATTACHMENT, OBSERVE_SUPERSEDED, HOSTING_IGNORED, RUNTIME_UNREACHABLE,
        RUNTIME_INCOMPATIBLE, RUNTIME_NO_ANSWER, RUNTIME_REJECTED, RUNTIME_ERROR, HANDSHAKE_REJECTED, CONNECTION_LOST,
        MODEL_DIFFERS, ENGINE_HALTED, ENGINE_SILENT, OBSERVATION_OUT_OF_SYNC, ENVIRONMENT_LEFT_BEHIND, ENVIRONMENT_LOST,
    )
}
