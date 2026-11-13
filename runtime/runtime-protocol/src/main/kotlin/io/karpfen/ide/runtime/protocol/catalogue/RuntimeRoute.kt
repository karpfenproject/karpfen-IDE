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

/** The HTTP method of a [RuntimeRoute]. */
public enum class HttpVerb {
    /** Reads and sends no request body. */
    GET,

    /** Acts. Every value travels in the query, and only a message to a feature also sends a request body. */
    POST,

    /** Uploads a modeling text as the request body. */
    PUT,
}

/**
 * What a [RuntimeRoute] takes as its request body.
 *
 * @property modelingText true for a modeling text, which the runtime reads with the readers of karpfen-dsl-tools. Only
 *   a route with such a body can be refused for its content, with the body that [UploadRejection] parses.
 */
public enum class BodyKind(public val modelingText: Boolean) {
    /** No body. */
    NONE(modelingText = false),

    /** A metamodel text (`.kmeta`). It is either the domain metamodel or the event definitions. */
    KMETA(modelingText = true),

    /** A model text (`.kmodel`). */
    KMODEL(modelingText = true),

    /** A state machine text (`.kstates`). */
    KSTATES(modelingText = true),

    /** A message to a feature, a plain text that the feature reads and answers. */
    FEATURE_MESSAGE(modelingText = false),
}

/** What the body of a successful answer of a [RuntimeRoute] holds. */
public enum class ResponseKind {
    /** Nothing the client reads. The runtime answers with an empty text. */
    EMPTY,

    /** A new environment key, `env-<epoch millis>`. */
    ENVIRONMENT_KEY,

    /** A new access key, `ak-<clientId>-<epoch millis>-<0..9999>`. */
    ACCESS_KEY,

    /** The health answer `{"status":"ok"}`. */
    HEALTH,

    /** The JSON array of the environments the runtime lists, with their attached model elements. */
    ENVIRONMENT_LIST,

    /** The state machine text that was uploaded for a model element, as it was uploaded. */
    STATE_MACHINE_SOURCE,

    /** The model snapshot of an environment, with its phase, revision, active stacks and root objects. */
    MODEL_SNAPSHOT,

    /** Feature names separated by [WireNames.FEATURE_SEPARATOR], in no particular order. An empty text names none. */
    FEATURE_NAMES,

    /** The answer of a feature to a message, a plain text taken as it is. */
    FEATURE_ANSWER,
}

/**
 * How long the client waits for an answer of a [RuntimeRoute]. The duration of each class is configurable on the
 * client's side.
 */
public enum class TimeoutClass {
    /** A call the runtime answers at once (10 s by default). */
    QUICK,

    /** An upload, which the runtime parses before it answers (30 s by default). */
    UPLOAD,

    /** A stop, which joins the engine for up to about 5 s before it answers (30 s by default). */
    STOP,

    /** A snapshot, which waits up to 10 s for a tick boundary of a running environment (30 s by default). */
    SNAPSHOT,
}

/**
 * The HTTP routes of the karpfen runtime with IDE support, as data. The entries are the 23 routes of the runtime's
 * `HTTPRoutes` in their order, followed by the three routes that its `ModelObservationRoutes` adds. Everything the
 * client sends is built from an entry, and the fake runtime dispatches by it.
 *
 * Query parameters are listed in the order the runtime checks them. When several are missing, the first of them names
 * the error `Missing required parameter: <name>`. A parameter that is present but empty counts as present.
 *
 * @property verb the HTTP method.
 * @property path the path below the runtime's base URL.
 * @property parameters the names of the query parameters, in the order the runtime checks them.
 * @property body what the request body holds.
 * @property response what the body of a successful answer holds.
 * @property timeout how long the client waits for the answer.
 */
public enum class RuntimeRoute(
    public val verb: HttpVerb,
    public val path: String,
    public val parameters: List<String>,
    public val body: BodyKind,
    public val response: ResponseKind,
    public val timeout: TimeoutClass,
) {
    /** Creates an environment and returns its key. The runtime never hands out a key twice. */
    CREATE_ENVIRONMENT(
        HttpVerb.POST, "/createEnvironment", emptyList(),
        BodyKind.NONE, ResponseKind.ENVIRONMENT_KEY, TimeoutClass.QUICK,
    ),

    /** Uploads the domain metamodel of a configuring environment. */
    SET_METAMODEL(
        HttpVerb.PUT, "/setMetamodel", listOf("envKey"),
        BodyKind.KMETA, ResponseKind.EMPTY, TimeoutClass.UPLOAD,
    ),

    /** Uploads the model of a configuring environment, read against its metamodel. */
    SET_MODEL(
        HttpVerb.PUT, "/setModel", listOf("envKey"),
        BodyKind.KMODEL, ResponseKind.EMPTY, TimeoutClass.UPLOAD,
    ),

    /** Uploads the event definitions of a configuring environment, read against the domain types of its metamodel. */
    SET_EVENT_DEFINITIONS(
        HttpVerb.PUT, "/setEventDefinitions", listOf("envKey"),
        BodyKind.KMETA, ResponseKind.EMPTY, TimeoutClass.UPLOAD,
    ),

    /** Attaches a state machine to the object with the key `attachedTo`. */
    SET_STATE_MACHINE(
        HttpVerb.PUT, "/setStateMachine", listOf("envKey", "attachedTo"),
        BodyKind.KSTATES, ResponseKind.EMPTY, TimeoutClass.UPLOAD,
    ),

    /** Sets the tick delay of a configuring environment, an integer number of milliseconds. */
    SET_TICK_DELAY(
        HttpVerb.POST, "/setTickDelay", listOf("envKey", "milliseconds"),
        BodyKind.NONE, ResponseKind.EMPTY, TimeoutClass.QUICK,
    ),

    /** Forces the active state of a machine. The runtime answers before the change applies at the next tick. */
    SET_ACTIVE_STATE(
        HttpVerb.POST, "/setActiveState", listOf("envKey", "modelElement", "state"),
        BodyKind.NONE, ResponseKind.EMPTY, TimeoutClass.QUICK,
    ),

    /** Sets the event time to live of a configuring environment, a whole number of milliseconds. */
    SET_EVENT_TTL(
        HttpVerb.POST, "/setEventTtl", listOf("envKey", "ttlMs"),
        BodyKind.NONE, ResponseKind.EMPTY, TimeoutClass.QUICK,
    ),

    /** Registers a client as an observer of one object, before activation. */
    REGISTER_OBJECT_OBSERVER(
        HttpVerb.POST, "/registerObjectObserver", listOf("envKey", "clientId", "objectId"),
        BodyKind.NONE, ResponseKind.EMPTY, TimeoutClass.QUICK,
    ),

    /** Registers a client as a listener of one event domain, before activation. */
    REGISTER_DOMAIN_LISTENER(
        HttpVerb.POST, "/registerDomainListener", listOf("envKey", "clientId", "domain"),
        BodyKind.NONE, ResponseKind.EMPTY, TimeoutClass.QUICK,
    ),

    /** Issues an access key for a WebSocket connection of a client, in any phase of an existing environment. */
    REGISTER_CLIENT_FOR_WEB_SOCKET(
        HttpVerb.POST, "/registerClientForWebSocket", listOf("clientId", "envKey"),
        BodyKind.NONE, ResponseKind.ACCESS_KEY, TimeoutClass.QUICK,
    ),

    /** Activates an environment by building its engine over the uploaded texts. This works once per environment. */
    RUN_ENVIRONMENT(
        HttpVerb.POST, "/runEnvironment", listOf("envKey"),
        BodyKind.NONE, ResponseKind.EMPTY, TimeoutClass.QUICK,
    ),

    /** Starts the engine of an activated environment and answers at once. */
    START_ENVIRONMENT(
        HttpVerb.POST, "/startEnvironment", listOf("envKey"),
        BodyKind.NONE, ResponseKind.EMPTY, TimeoutClass.QUICK,
    ),

    /** Stops an environment, joining its engine for up to about 5 s, and closes its WebSocket sessions. */
    STOP_ENVIRONMENT(
        HttpVerb.POST, "/stopEnvironment", listOf("envKey"),
        BodyKind.NONE, ResponseKind.EMPTY, TimeoutClass.STOP,
    ),

    /** Answers whether the runtime is up. */
    HEALTH(
        HttpVerb.GET, "/health", emptyList(),
        BodyKind.NONE, ResponseKind.HEALTH, TimeoutClass.QUICK,
    ),

    /** Lists the activated, running, halted and stopped environments with their attached model elements. */
    LIST_ENVIRONMENTS(
        HttpVerb.GET, "/observatory/environments", emptyList(),
        BodyKind.NONE, ResponseKind.ENVIRONMENT_LIST, TimeoutClass.QUICK,
    ),

    /** Answers the state machine text uploaded for a model element. */
    STATE_MACHINE_SOURCE(
        HttpVerb.GET, "/observatory/statemachine", listOf("envKey", "modelElement"),
        BodyKind.NONE, ResponseKind.STATE_MACHINE_SOURCE, TimeoutClass.QUICK,
    ),

    /**
     * Issues an access key and registers the client for the stack changes of one machine. The model element `*`
     * registers it for every machine.
     */
    REGISTER_OBSERVATORY_CLIENT(
        HttpVerb.POST, "/observatory/registerClient", listOf("clientId", "envKey", "modelElement"),
        BodyKind.NONE, ResponseKind.ACCESS_KEY, TimeoutClass.QUICK,
    ),

    /** Answers the names of every feature the runtime knows. */
    GET_REGISTERED_FEATURES(
        HttpVerb.GET, "/getRegisteredFeatures", emptyList(),
        BodyKind.NONE, ResponseKind.FEATURE_NAMES, TimeoutClass.QUICK,
    ),

    /**
     * Switches a feature on in an environment, together with the features it needs. It works in every phase of an
     * existing environment, and it is refused for a feature that is on already.
     */
    ACTIVATE_FEATURE(
        HttpVerb.POST, "/activateFeature", listOf("envKey", "featureName"),
        BodyKind.NONE, ResponseKind.EMPTY, TimeoutClass.QUICK,
    ),

    /** Answers the names of the features that are on in an environment. */
    GET_ACTIVE_FEATURES(
        HttpVerb.GET, "/getActiveFeatures", listOf("envKey"),
        BodyKind.NONE, ResponseKind.FEATURE_NAMES, TimeoutClass.QUICK,
    ),

    /**
     * Switches a feature off in an environment, together with the features that need it. It is refused for a feature
     * that is not on.
     */
    DEACTIVATE_FEATURE(
        HttpVerb.POST, "/deactivateFeature", listOf("envKey", "featureName"),
        BodyKind.NONE, ResponseKind.EMPTY, TimeoutClass.QUICK,
    ),

    /** Sends a message to a feature that is on in an environment and answers what the feature answers. */
    SEND_MESSAGE_TO_FEATURE(
        HttpVerb.POST, "/sendMessageToFeature", listOf("envKey", "featureName"),
        BodyKind.FEATURE_MESSAGE, ResponseKind.FEATURE_ANSWER, TimeoutClass.QUICK,
    ),

    /** Answers the model snapshot of an environment, read between two ticks. */
    MODEL_SNAPSHOT(
        HttpVerb.GET, "/observatory/model", listOf("envKey"),
        BodyKind.NONE, ResponseKind.MODEL_SNAPSHOT, TimeoutClass.SNAPSHOT,
    ),

    /** Subscribes a client with an attached WebSocket session to every object and stack change of an environment. */
    SUBSCRIBE_MODEL(
        HttpVerb.POST, "/observatory/subscribeModel", listOf("envKey", "clientId"),
        BodyKind.NONE, ResponseKind.EMPTY, TimeoutClass.QUICK,
    ),

    /** Ends the model subscription of a client. Its other subscriptions stay. */
    UNSUBSCRIBE_MODEL(
        HttpVerb.POST, "/observatory/unsubscribeModel", listOf("envKey", "clientId"),
        BodyKind.NONE, ResponseKind.EMPTY, TimeoutClass.QUICK,
    ),
}
