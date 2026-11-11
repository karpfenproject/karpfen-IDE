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

import io.karpfen.ide.runtime.protocol.catalogue.BodyKind
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText

/**
 * Classifies the body of a rejection by the runtime's texts. A rejection has the status 400 or 409.
 *
 * A body counts as one of the [RuntimeText]s only if it is that whole text. The texts are disjoint, so at most one
 * matches. A body that matches none of them becomes [RejectionKind.CONTENT_REJECTED] on a route that uploads a body.
 * There the runtime's parser refused the text with a message of its own and without a position. On every other route
 * such a body becomes [RejectionKind.UNCLASSIFIED]. The object holds no state.
 */
internal object RejectionClassifier {

    /** The rejection of [route] with [status] and [body], as a [RuntimeFailure.Rejected]. */
    fun classify(route: RuntimeRoute, status: Int, body: String): RuntimeFailure.Rejected {
        for (text in RuntimeText.entries) {
            val captured = text.match(body) ?: continue
            return RuntimeFailure.Rejected(route, status, text.kind, body, captured)
        }
        val kind = if (route.body != BodyKind.NONE && status in REJECTION_STATUSES) {
            RejectionKind.CONTENT_REJECTED
        } else {
            RejectionKind.UNCLASSIFIED
        }
        return RuntimeFailure.Rejected(route, status, kind, body, emptyMap())
    }

    /** The statuses with which the runtime rejects a call: 400 for an illegal argument, 409 for an illegal state. */
    val REJECTION_STATUSES: Set<Int> = setOf(400, 409)
}
