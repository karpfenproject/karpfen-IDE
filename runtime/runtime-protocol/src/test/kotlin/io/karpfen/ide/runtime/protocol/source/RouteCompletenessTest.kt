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
package io.karpfen.ide.runtime.protocol.source

import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the completeness of the route catalogue. The routes the runtime declares in its `HTTPRoutes.kt`, followed by
 * those of its `ModelObservationRoutes.kt`, are exactly the catalogue's routes in the catalogue's order. So a route the
 * runtime adds, removes or renames fails here before any client code meets it. The KDoc of [RuntimeSourceAnchorTest]
 * describes what to do when the karpfen-runtime submodule moves.
 */
class RouteCompletenessTest {

    private val checkout = RuntimeSourceCheckout.fromSystemProperty()

    @Test
    fun `the runtime declares exactly the catalogue's routes in the catalogue's order`() {
        val declared = listOf(HTTP_ROUTES, OBSERVATION_ROUTES).flatMap { file ->
            RouteDeclarations.of(checkout.text(file)).routes.map { "${it.verb} ${it.path}" }
        }

        assertEquals(RuntimeRoute.entries.map { "${it.verb} ${it.path}" }, declared)
    }

    @Test
    fun `route declarations are found with their blocks and parameter reads`() {
        val source = """
            routing {
                post("/first") {
                    val a = call.request.queryParameters["a"]
                    val b = call.request.queryParameters["b"]
                }
                get("/second") {
                    obj.put("envKey", value)
                }
            }
        """

        val routes = RouteDeclarations.of(source).routes

        assertEquals(listOf("POST /first", "GET /second"), routes.map { "${it.verb} ${it.path}" })
        assertEquals(listOf(listOf("a", "b"), emptyList()), routes.map { it.parameterReads })
    }

    private companion object {
        /** The runtime's file of its original routes. */
        const val HTTP_ROUTES = "src/main/kotlin/io/karpfen/server/HTTPRoutes.kt"

        /** The runtime's file of the model observation routes. */
        const val OBSERVATION_ROUTES = "src/main/kotlin/io/karpfen/observation/ModelObservationRoutes.kt"
    }
}
