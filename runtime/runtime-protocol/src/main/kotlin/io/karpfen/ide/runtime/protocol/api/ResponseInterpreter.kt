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
package io.karpfen.ide.runtime.protocol.api

import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.message.ProtocolViolation
import io.karpfen.ide.runtime.protocol.message.ViolationKind
import io.karpfen.ide.runtime.protocol.message.excerpt
import io.karpfen.ide.runtime.protocol.transport.HttpOutcome

/**
 * Interprets the runtime's answer to a call of a route: 200 is a success whose body the route's [SuccessBody] reads
 * (a body it cannot read is a [RuntimeFailure.ProtocolViolated] with [ViolationKind.UNEXPECTED_BODY]); 400 and 409 are
 * rejections classified by [RejectionClassifier]; 500 is a [RuntimeFailure.ServerFault]; every other status, such as the
 * 404 of a path the runtime lacks or the 405 of a wrong method, is a [RuntimeFailure.UnexpectedStatus]. Stateless.
 */
internal object ResponseInterpreter {

    /** The status of a success. */
    private const val OK = 200

    /** The status of an internal error of the runtime. */
    private const val SERVER_ERROR = 500

    /** The result of [route] from [answer], reading a success with [success]. */
    fun <T> interpret(route: RuntimeRoute, answer: HttpOutcome.Answered, success: SuccessBody<T>): RuntimeResult<T> =
        when (answer.status) {
            OK -> when (val reading = success.read(answer.body)) {
                is BodyReading.Read -> RuntimeResult.Ok(reading.value)
                is BodyReading.Unexpected -> RuntimeResult.Failed(
                    RuntimeFailure.ProtocolViolated(
                        route,
                        ProtocolViolation(ViolationKind.UNEXPECTED_BODY, reading.detail, answer.body.excerpt()),
                    ),
                )
            }
            else -> RuntimeResult.Failed(failure(route, answer))
        }

    /** The failure of [route] that an [answer] other than 200 stands for. */
    fun failure(route: RuntimeRoute, answer: HttpOutcome.Answered): RuntimeFailure =
        when (answer.status) {
            in RejectionClassifier.REJECTION_STATUSES -> RejectionClassifier.classify(route, answer.status, answer.body)
            SERVER_ERROR -> RuntimeFailure.ServerFault(route, answer.body)
            else -> RuntimeFailure.UnexpectedStatus(route, answer.status, answer.body)
        }
}
