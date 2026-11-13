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

import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.catalogue.UploadRejection

/**
 * Classifies the body of a rejection by the runtime's texts. A rejection has the status 400 or 409.
 *
 * A body counts as one of the [RuntimeText]s only if it is that whole text. The texts are disjoint, so at most one
 * matches. A body that matches none of them becomes [RejectionKind.CONTENT_REJECTED] if the route uploads a modeling
 * text, the status is 400 and [UploadRejection.parse] reads the body. That is the runtime's answer to an upload whose
 * text has errors, and the rejection carries the parsed body. This is the only place that parses it.
 *
 * Every other body becomes [RejectionKind.UNCLASSIFIED], on an upload route as on any other route. The runtime refuses
 * the content of every upload with the body of [UploadRejection], so a text of another form on an upload route is not
 * known to be about the uploaded text. It may come from a runtime of another version or from something between the
 * client and the runtime, and the client does not guess what it means. The object holds no state.
 */
internal object RejectionClassifier {

    /** The rejection of [route] with [status] and [body], as a [RuntimeFailure.Rejected]. */
    fun classify(route: RuntimeRoute, status: Int, body: String): RuntimeFailure.Rejected {
        for (text in RuntimeText.entries) {
            val captured = text.match(body) ?: continue
            return RuntimeFailure.Rejected(route, status, text.kind, body, captured)
        }
        val content = if (route.body.modelingText && status == CONTENT_STATUS) UploadRejection.parse(body) else null
        val kind = if (content != null) RejectionKind.CONTENT_REJECTED else RejectionKind.UNCLASSIFIED
        return RuntimeFailure.Rejected(route, status, kind, body, emptyMap(), content)
    }

    /** The statuses with which the runtime rejects a call: 400 for an illegal argument, 409 for an illegal state. */
    val REJECTION_STATUSES: Set<Int> = setOf(400, 409)

    /** The status with which the runtime refuses an upload whose text has errors. */
    private const val CONTENT_STATUS: Int = 400
}
