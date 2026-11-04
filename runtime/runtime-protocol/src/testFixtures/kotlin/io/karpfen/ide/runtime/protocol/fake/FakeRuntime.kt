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
package io.karpfen.ide.runtime.protocol.fake

import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.transport.ConnectOutcome
import io.karpfen.ide.runtime.protocol.transport.HttpOutcome
import io.karpfen.ide.runtime.protocol.transport.RuntimeHttp
import io.karpfen.ide.runtime.protocol.transport.RuntimeTransports
import io.karpfen.ide.runtime.protocol.transport.TextRequest
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import io.karpfen.ide.runtime.protocol.transport.WebSocketConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * A stand-in for the karpfen runtime at the client's two seams that only answers what each test programmed. It never
 * simulates the runtime: it keeps no environments, phases, sessions, subscriptions, revisions, queues, ticks or models,
 * parses no modeling text and no JSON, checks no lifecycle, handles no event and knows no time to live. Every
 * rejection, push, snapshot and close a test sees comes from that test. Whether the client works with the real runtime
 * is shown by the runtime goldens, the source anchors and the scenarios against a runtime in Docker, never by this fake.
 *
 * **HTTP.** A test programs replies per route ([respond], [fail], [hang]), each with an optional delay on the test's
 * time and optionally only for requests whose parameters match. A request takes the first reply programmed for its
 * route that matches it; each reply is used once. Without one, a route answers a fixed default:
 * - `POST /createEnvironment`: 200 with the next key of the list `env-1`, `env-2`, …;
 * - the two key-issuing routes: 200 with the next access key in the runtime's shape, `ak-<clientId>-<millis>-<n>`, the
 *   millis fixed at [ACCESS_KEY_MILLIS] and `n` counting the issued keys from 1 (see [accessKey]);
 * - `GET /health`: the runtime's health answer [HEALTH_BODY];
 * - `GET /observatory/model`: 404 with an empty body, as a runtime without IDE support answers, unless a test programmed
 *   a snapshot;
 * - a path the catalogue does not know: 404;
 * - every other route: 200 with an empty body.
 * A test may attach follow-ups to a request ([after]), such as frames the runtime would push after its answer; they run
 * in [scope] once the answer was handed back.
 *
 * **WebSocket.** Every connect opens a [FakeWebSocketLink], unless the test programmed the connect to fail
 * ([refuseConnect]) or never to complete ([hangConnect]). The link records the handshake and accepts it silently, unless
 * the test programmed a close for it ([closeHandshake]). The test pushes frames on a link ([FakeWebSocketLink.push]) or
 * to the one open link of a client ([pushToClient]), and closes or drops links.
 *
 * **Inspection.** The requests ([requests]), the links with their handshakes and client frames ([links], [handshakes],
 * [clientFrames]) and the URLs connected to ([connectedUrls]).
 *
 * Threading: every function may be called from any thread. Delays run on the caller's dispatcher, so on the test's
 * virtual time.
 *
 * @param scope where the follow-ups of requests run, usually the test's background scope.
 */
public class FakeRuntime(private val scope: CoroutineScope) : RuntimeHttp, WebSocketConnector {

    /** One reply a test programmed for a route. */
    private class Programmed(val route: RuntimeRoute, val where: Map<String, String>, val reply: Reply, val delay: Duration)

    /** What a programmed reply does. */
    private sealed interface Reply {
        /** Answers with [response]. */
        data class Answer(val response: FakeResponse) : Reply

        /** Fails with [failure]. */
        data class Failure(val failure: TransportFailure) : Reply

        /** Never answers. */
        data object Silence : Reply
    }

    /** One follow-up a test attached to the next matching request of a route. */
    private class FollowUp(val route: RuntimeRoute, val where: Map<String, String>, val action: FakeRuntime.(FakeRequest) -> Unit)

    /** What the next connect does instead of opening a link. */
    private sealed interface ConnectStep {
        /** The connect fails with [failure]. */
        data class Refuse(val failure: TransportFailure) : ConnectStep

        /** The connect never completes. */
        data object Hang : ConnectStep
    }

    /** Guards the programs and the records. */
    private val lock = Any()

    /** The replies not used yet, in programming order. */
    private val replies = ArrayList<Programmed>()

    /** The follow-ups not used yet, in programming order. */
    private val followUps = ArrayList<FollowUp>()

    /** The requests so far. */
    private val recordedRequests = ArrayList<FakeRequest>()

    /** The connect steps not used yet. */
    private val connectSteps = ArrayDeque<ConnectStep>()

    /** The handshake closes not used yet, one per link opened from now on. */
    private val handshakeCloses = ArrayDeque<FakeClose>()

    /** The links opened so far. */
    private val openedLinks = ArrayList<FakeWebSocketLink>()

    /** The URLs connected to so far. */
    private val urls = ArrayList<String>()

    /** The default environment keys handed out so far. */
    private var defaultEnvironments = 0

    /** The default access keys handed out so far. */
    private var defaultAccessKeys = 0

    /** The fake's two seams as the client's transports; closing them releases nothing. */
    public fun transports(): RuntimeTransports = RuntimeTransports(http = this, webSockets = this)

    /**
     * The next request of [route] whose parameters include [where] is answered with [response] after [delay] on the
     * caller's time.
     */
    public fun respond(
        route: RuntimeRoute,
        response: FakeResponse,
        where: Map<String, String> = emptyMap(),
        delay: Duration = Duration.ZERO,
    ): FakeRuntime = program(Programmed(route, where, Reply.Answer(response), delay))

    /** The next request of [route] whose parameters include [where] fails with [failure] after [delay]. */
    public fun fail(
        route: RuntimeRoute,
        failure: TransportFailure,
        where: Map<String, String> = emptyMap(),
        delay: Duration = Duration.ZERO,
    ): FakeRuntime = program(Programmed(route, where, Reply.Failure(failure), delay))

    /** The next request of [route] whose parameters include [where] is never answered; its caller has to give up. */
    public fun hang(route: RuntimeRoute, where: Map<String, String> = emptyMap()): FakeRuntime =
        program(Programmed(route, where, Reply.Silence, Duration.ZERO))

    /**
     * After the next request of [route] whose parameters include [where] was answered, [action] runs in the fake's
     * scope with that request, for example to push the frames the runtime would send next.
     */
    public fun after(
        route: RuntimeRoute,
        where: Map<String, String> = emptyMap(),
        action: FakeRuntime.(FakeRequest) -> Unit,
    ): FakeRuntime = also { synchronized(lock) { followUps += FollowUp(route, where, action) } }

    /** The next connect fails with [failure] instead of opening a link. */
    public fun refuseConnect(failure: TransportFailure): FakeRuntime =
        also { synchronized(lock) { connectSteps.addLast(ConnectStep.Refuse(failure)) } }

    /** The next connect never completes. */
    public fun hangConnect(): FakeRuntime = also { synchronized(lock) { connectSteps.addLast(ConnectStep.Hang) } }

    /**
     * The handshake of the next link opened from now on is answered with a close frame of [code] and [reason], as the
     * runtime refuses a handshake.
     */
    public fun closeHandshake(code: Int, reason: String): FakeRuntime =
        also { synchronized(lock) { handshakeCloses.addLast(FakeClose(code, reason)) } }

    /** Every request so far, in the order it arrived. */
    public val requests: List<FakeRequest> get() = synchronized(lock) { recordedRequests.toList() }

    /** The requests of [route] so far, in the order they arrived. */
    public fun requests(route: RuntimeRoute): List<FakeRequest> = requests.filter { it.route == route }

    /** Every link opened so far, in the order the client connected. */
    public val links: List<FakeWebSocketLink> get() = synchronized(lock) { openedLinks.toList() }

    /** The links whose handshake names the client [clientId], in the order the client connected. */
    public fun linksOf(clientId: String): List<FakeWebSocketLink> = links.filter { it.belongsTo(clientId) }

    /** The handshakes of the links that received one, in the order the links were opened. */
    public val handshakes: List<String> get() = links.mapNotNull { it.handshake }

    /** The frames the client sent after the handshakes, link by link in the order the links were opened. */
    public val clientFrames: List<String> get() = links.flatMap { it.clientFrames }

    /** The URLs the client connected to, in order. */
    public val connectedUrls: List<String> get() = synchronized(lock) { urls.toList() }

    /**
     * Pushes [frame] on the one open link whose handshake names [clientId].
     *
     * @throws IllegalStateException if the client has no open link or more than one; the test then pushes on the link
     *   it means with [FakeWebSocketLink.push].
     */
    public fun pushToClient(clientId: String, frame: String) {
        val open = linksOf(clientId).filter { it.isOpen }
        check(open.size == 1) { "the client $clientId has ${open.size} open links; push on the link you mean" }
        open.single().push(frame)
    }

    override suspend fun exchange(request: TextRequest): HttpOutcome {
        val route = RuntimeRoute.entries.firstOrNull { it.verb == request.verb && it.path == request.path }
        val received = FakeRequest(route, request.verb, request.path, request.query, request.body)
        val (programmed, followUp) = synchronized(lock) {
            recordedRequests += received
            takeReply(received) to takeFollowUp(received)
        }
        delay(programmed?.delay ?: Duration.ZERO)
        val outcome = when (val what = programmed?.reply ?: Reply.Answer(defaultResponse(received))) {
            is Reply.Answer -> HttpOutcome.Answered(what.response.status, what.response.body, what.response.contentType)
            is Reply.Failure -> HttpOutcome.Failed(what.failure)
            Reply.Silence -> awaitCancellation()
        }
        followUp?.let { scope.launch { it.action(this@FakeRuntime, received) } }
        return outcome
    }

    override suspend fun connect(url: String): ConnectOutcome {
        val step = synchronized(lock) {
            urls += url
            connectSteps.removeFirstOrNull()
        }
        return when (step) {
            is ConnectStep.Refuse -> ConnectOutcome.Failed(step.failure)
            ConnectStep.Hang -> awaitCancellation()
            null -> ConnectOutcome.Connected(
                synchronized(lock) { FakeWebSocketLink(url, handshakeCloses.removeFirstOrNull()).also { openedLinks += it } },
            )
        }
    }

    /** Appends [programmed] to the replies. */
    private fun program(programmed: Programmed): FakeRuntime = also { synchronized(lock) { replies += programmed } }

    /** Takes the first reply programmed for [request]; called under the lock. */
    private fun takeReply(request: FakeRequest): Programmed? {
        val index = replies.indexOfFirst { it.route == request.route && request.matches(it.where) }
        return if (index < 0) null else replies.removeAt(index)
    }

    /** Takes the first follow-up attached to [request]; called under the lock. */
    private fun takeFollowUp(request: FakeRequest): FollowUp? {
        val index = followUps.indexOfFirst { it.route == request.route && request.matches(it.where) }
        return if (index < 0) null else followUps.removeAt(index)
    }

    /** The fixed default answer to [request], for a route without a programmed reply. */
    private fun defaultResponse(request: FakeRequest): FakeResponse =
        when (request.route) {
            null, RuntimeRoute.MODEL_SNAPSHOT -> FakeResponse(FakeResponse.NOT_FOUND)
            RuntimeRoute.CREATE_ENVIRONMENT -> FakeResponse.ok(environmentKey(synchronized(lock) { ++defaultEnvironments }))
            RuntimeRoute.REGISTER_CLIENT_FOR_WEB_SOCKET, RuntimeRoute.REGISTER_OBSERVATORY_CLIENT ->
                FakeResponse.ok(accessKey(request.parameter(CLIENT_ID).orEmpty(), synchronized(lock) { ++defaultAccessKeys }))
            RuntimeRoute.HEALTH -> FakeResponse(FakeResponse.OK, HEALTH_BODY, HEALTH_CONTENT_TYPE)
            else -> FakeResponse.ok()
        }

    public companion object {
        /** The body of the runtime's health answer. */
        public const val HEALTH_BODY: String = """{"status":"ok"}"""

        /** The content type of the runtime's health answer. */
        public const val HEALTH_CONTENT_TYPE: String = "application/json"

        /** The fixed millisecond part of the default access keys. */
        public const val ACCESS_KEY_MILLIS: Long = 1_761_555_601_234L

        /** The query parameter that names the client of a key-issuing request. */
        private const val CLIENT_ID = "clientId"

        /** The [n]-th default environment key, counted from 1: `env-<n>`. */
        public fun environmentKey(n: Int): String = WireNames.ENVIRONMENT_KEY_PREFIX + n

        /**
         * The [n]-th default access key, counted from 1 over every key the fake issued by default, for [clientId]:
         * `ak-<clientId>-<millis>-<n>` in the runtime's shape, with [ACCESS_KEY_MILLIS] as the millis.
         */
        public fun accessKey(clientId: String, n: Int): String = "${WireNames.ACCESS_KEY_PREFIX}$clientId-$ACCESS_KEY_MILLIS-$n"
    }
}
