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
    /** Reads; no request body. */
    GET,

    /** Acts; no request body, every value travels in the query. */
    POST,

    /** Uploads a modeling text as the request body. */
    PUT,
}

/** What a [RuntimeRoute] takes as its request body. */
public enum class BodyKind {
    /** No body. */
    NONE,

    /** A metamodel text (`.kmeta`): the domain metamodel or the event definitions. */
    KMETA,

    /** A model text (`.kmodel`). */
    KMODEL,

    /** A state machine text (`.kstates`). */
    KSTATES,
}

/** What the body of a successful answer of a [RuntimeRoute] holds. */
public enum class ResponseKind {
    /** Nothing the client reads; the runtime answers with an empty text. */
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

    /** The model snapshot: phase, revision, active stacks and the root objects of an environment. */
    MODEL_SNAPSHOT,
}

/**
 * How long the client waits for an answer of a [RuntimeRoute]; the durations of the classes are configurable on the
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
 * The HTTP routes of the karpfen runtime with IDE support, as data: the 18 routes of its `HTTPRoutes` in their order,
 * followed by the three routes its `ModelObservationRoutes` adds. Everything the client sends is built from an entry,
 * and the fake runtime dispatches by it.
 *
 * Query parameters are listed in the order the runtime checks them: when several are missing, the first of them names
 * the error (`Missing required parameter: <name>`). A parameter that is present but empty counts as present.
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
    /** Creates an environment and answers its key, which the runtime never hands out twice. */
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

    /** Forces the active state of a machine; answered before the change applies at the next tick. */
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

    /** Activates an environment: builds its engine over the uploaded texts; once per environment. */
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

    /** Issues an access key and registers the client for the stack changes of one machine, or of every machine with `*`. */
    REGISTER_OBSERVATORY_CLIENT(
        HttpVerb.POST, "/observatory/registerClient", listOf("clientId", "envKey", "modelElement"),
        BodyKind.NONE, ResponseKind.ACCESS_KEY, TimeoutClass.QUICK,
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

    /** Ends the model subscription of a client; its other subscriptions stay. */
    UNSUBSCRIBE_MODEL(
        HttpVerb.POST, "/observatory/unsubscribeModel", listOf("envKey", "clientId"),
        BodyKind.NONE, ResponseKind.EMPTY, TimeoutClass.QUICK,
    ),
}
