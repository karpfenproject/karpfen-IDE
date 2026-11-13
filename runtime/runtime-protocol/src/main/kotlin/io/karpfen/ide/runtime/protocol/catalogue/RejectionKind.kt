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
package io.karpfen.ide.runtime.protocol.catalogue

/**
 * Why the runtime rejected a call with 400 or 409. There is one kind per text of [RuntimeText]. Two kinds come on top.
 * [CONTENT_REJECTED] stands for an upload whose text has errors, answered with the body that [UploadRejection]
 * parses, and [UNCLASSIFIED] stands for any other text.
 */
public enum class RejectionKind {
    /** A query parameter is missing. The text names the first missing one in the order the runtime checks them. */
    MISSING_PARAMETER,

    /** The tick delay or the time to live is missing or not a number. */
    INVALID_PARAMETER,

    /** The runtime does not know the environment. Either it never existed, or the runtime restarted. */
    UNKNOWN_ENVIRONMENT,

    /** The environment is active, so it cannot be configured or activated again. */
    ALREADY_ACTIVE,

    /** A model was uploaded before the metamodel. */
    METAMODEL_REQUIRED,

    /** The environment was activated without a metamodel or without a model. */
    MODELS_REQUIRED,

    /** A state machine is attached to an object key the model does not have, so the activation was refused. */
    UNKNOWN_ATTACHMENT,

    /** The environment was started or stopped before it was activated. */
    NOT_ACTIVATED,

    /** A state was forced in an environment without an execution thread. */
    NO_EXECUTION_THREAD,

    /** A state was forced in an environment that is not active. */
    NOT_ACTIVE,

    /** A state was forced for a model element without a state machine. */
    UNKNOWN_STATE_MACHINE,

    /** A forced state is not a state of the machine. */
    UNKNOWN_STATE,

    /** No state machine text was uploaded for the model element. */
    NO_STATE_MACHINE_SOURCE,

    /** The snapshot of a configuring environment without a model. */
    NO_MODEL,

    /**
     * The snapshot found no tick boundary within the runtime's wait. A tick of a running environment, or the last tick
     * of a stopped one, did not end.
     */
    TICK_BOUNDARY_TIMEOUT,

    /** The model holds an object reachable from itself, which the runtime cannot write as JSON. */
    REFERENCE_LOOP,

    /** The client subscribed to the model without an attached WebSocket session. */
    NO_SESSION,

    /** The runtime does not know the named feature. */
    UNKNOWN_FEATURE,

    /** The feature to switch on is on already in the environment. */
    FEATURE_ALREADY_ACTIVE,

    /** The feature to switch off is not on in the environment. */
    FEATURE_NOT_ACTIVE,

    /** A message went to a feature that is not on in the environment. */
    FEATURE_NOT_ACTIVATED,

    /** Another switch of the environment's features was under way, so this one was refused. */
    FEATURES_BUSY,

    /** The runtime failed with an exception without a message. */
    UNEXPECTED_ERROR,

    /**
     * The runtime's reader found errors in an uploaded modeling text and refused it with 400. The body names the upload
     * and lists every error with its line and column, as [UploadRejection] describes. The environment stays as it was.
     */
    CONTENT_REJECTED,

    /** A rejection whose text is none of the known ones. */
    UNCLASSIFIED,
}
