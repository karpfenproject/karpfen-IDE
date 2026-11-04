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
package io.karpfen.ide.runtime.protocol.doubles

import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.transport.HttpOutcome
import io.karpfen.ide.runtime.protocol.transport.RuntimeHttp
import io.karpfen.ide.runtime.protocol.transport.TextRequest
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * A double of the HTTP seam that answers with the replies a test scripted, in order, whatever the route, and with 200
 * and an empty body once the script is used up. It records every request and how many exchanges ran at once. Replies
 * may come after a delay, which runs on the test's virtual time, or never.
 */
class ScriptedHttp : RuntimeHttp {

    /**
     * One scripted reply.
     *
     * @property outcome the outcome, or null for a reply that never comes.
     * @property after the delay before it comes.
     */
    private class Reply(val outcome: HttpOutcome?, val after: Duration)

    /** Guards the script and the records. */
    private val lock = Any()

    /** The replies still scripted, in order. */
    private val replies = ArrayDeque<Reply>()

    /** The requests so far, in order. */
    private val recorded = ArrayList<TextRequest>()

    /** The exchanges running now. */
    private var inFlight = 0

    /** The most exchanges that ran at the same time so far. */
    private var mostInFlight = 0

    /** The requests in the order they arrived. */
    val requests: List<TextRequest> get() = synchronized(lock) { recorded.toList() }

    /** The paths of the requests in the order they arrived. */
    val paths: List<String> get() = requests.map { it.path }

    /** The most exchanges that ran at the same time. */
    val mostOverlapping: Int get() = synchronized(lock) { mostInFlight }

    /** Scripts an answer with [status] and [body], given after [after]. */
    fun answer(status: Int, body: String = "", after: Duration = Duration.ZERO): ScriptedHttp =
        script(Reply(HttpOutcome.Answered(status, body, null), after))

    /** Scripts a 200 answer with [body]. */
    fun ok(body: String = ""): ScriptedHttp = answer(OK, body)

    /** Scripts a rejection with [status] and the runtime's [text]. */
    fun reject(status: Int, text: String): ScriptedHttp = answer(status, text)

    /** Scripts a transport [failure]. */
    fun fail(failure: TransportFailure): ScriptedHttp = script(Reply(HttpOutcome.Failed(failure), Duration.ZERO))

    /** Scripts an exchange that never answers. */
    fun hang(): ScriptedHttp = script(Reply(null, Duration.ZERO))

    override suspend fun exchange(request: TextRequest): HttpOutcome {
        val reply = synchronized(lock) {
            recorded += request
            inFlight++
            mostInFlight = maxOf(mostInFlight, inFlight)
            replies.removeFirstOrNull()
        }
        try {
            if (reply == null) return HttpOutcome.Answered(OK, "", null)
            delay(reply.after)
            return reply.outcome ?: awaitCancellation()
        } finally {
            synchronized(lock) { inFlight-- }
        }
    }

    /** Appends [reply] to the script. */
    private fun script(reply: Reply): ScriptedHttp {
        synchronized(lock) { replies.addLast(reply) }
        return this
    }

    /** The status of a success and the route of a request. */
    companion object {
        /** The status of a success. */
        const val OK = 200

        /** The route a request went to, found by its path and verb. */
        fun routeOf(request: TextRequest): RuntimeRoute =
            RuntimeRoute.entries.single { it.path == request.path && it.verb == request.verb }
    }
}
