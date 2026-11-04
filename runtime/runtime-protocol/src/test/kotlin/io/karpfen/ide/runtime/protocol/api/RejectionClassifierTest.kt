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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [RejectionClassifier]: every text of the runtime is classified as its kind with its placeholder values, also
 * with an empty environment key; texts only match whole; an unknown text is a content rejection on the upload routes
 * and unclassified everywhere else.
 */
class RejectionClassifierTest {

    /** A sample value for every placeholder of the runtime's texts. */
    private val samples = mapOf(
        "env" to "env-1761555600000",
        "client" to "karpfen-ide-4f2c9a1b7e03",
        "name" to "envKey",
        "element" to "living room",
        "state" to "full power",
        "keys" to "turtle, lamp",
        "id" to "APB 2101",
        "ms" to "10000",
    )

    @Test
    fun `every text of the runtime is classified as its kind with its placeholder values`() {
        RuntimeText.entries.forEach { text ->
            val placeholders = Regex("""\{(\w+)}""").findAll(text.template).map { it.groupValues[1] }.toList()
            val values = placeholders.associateWith { samples.getValue(it) }
            val body = text.render(values)

            val rejection = RejectionClassifier.classify(RuntimeRoute.SET_MODEL, 409, body)

            assertEquals(text.kind, rejection.kind, body)
            assertEquals(values, rejection.captured, body)
            assertEquals(body, rejection.message)
            assertEquals(409, rejection.status)
        }
    }

    @Test
    fun `an empty environment key still names its text`() {
        val rejection = RejectionClassifier.classify(RuntimeRoute.RUN_ENVIRONMENT, 400, "Environment with key  does not exist")

        assertEquals(RejectionKind.UNKNOWN_ENVIRONMENT, rejection.kind)
        assertEquals(mapOf("env" to ""), rejection.captured)
    }

    @Test
    fun `the quoted and unquoted texts without an execution thread are told apart`() {
        assertEquals(
            RejectionKind.NOT_ACTIVATED,
            RejectionClassifier.classify(RuntimeRoute.START_ENVIRONMENT, 409, "Environment with key 'env-1' has no execution thread").kind,
        )
        assertEquals(
            RejectionKind.NO_EXECUTION_THREAD,
            RejectionClassifier.classify(RuntimeRoute.SET_ACTIVE_STATE, 409, "Environment with key env-1 has no execution thread").kind,
        )
    }

    @Test
    fun `an unknown text is a content rejection on an upload route and unclassified elsewhere`() {
        val parserText = "Unknown class type 'Lmap' for object 'lamp'"
        val uploads = RuntimeRoute.entries.filter { it.body != BodyKind.NONE }

        assertEquals(
            listOf(RuntimeRoute.SET_METAMODEL, RuntimeRoute.SET_MODEL, RuntimeRoute.SET_EVENT_DEFINITIONS, RuntimeRoute.SET_STATE_MACHINE),
            uploads,
        )
        uploads.forEach { route ->
            listOf(400, 409).forEach { status ->
                val rejection = RejectionClassifier.classify(route, status, parserText)
                assertEquals(RejectionKind.CONTENT_REJECTED, rejection.kind)
                assertEquals(emptyMap(), rejection.captured)
            }
        }
        (RuntimeRoute.entries - uploads.toSet()).forEach { route ->
            assertEquals(RejectionKind.UNCLASSIFIED, RejectionClassifier.classify(route, 400, parserText).kind)
        }
    }

    @Test
    fun `a text only matches as a whole body`() {
        listOf(
            "Missing required parameter: envKey\n",
            " Unexpected error",
            "Error: Environment with key env-1 does not exist",
        ).forEach { body ->
            assertEquals(RejectionKind.UNCLASSIFIED, RejectionClassifier.classify(RuntimeRoute.HEALTH, 400, body).kind, body)
        }
    }
}
