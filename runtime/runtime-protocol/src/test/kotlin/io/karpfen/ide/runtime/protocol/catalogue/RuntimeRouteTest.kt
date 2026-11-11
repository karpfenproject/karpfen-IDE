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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the 21 routes of [RuntimeRoute] for the runtime with IDE support, in the order of its route files. Each route
 * has its verb, path, parameters in the order the runtime checks them, body, answer and timeout class.
 */
class RuntimeRouteTest {

    @Test
    fun `the catalogue holds the 21 routes in the order of the runtime's route files`() {
        assertEquals(
            listOf(
                "POST /createEnvironment" to emptyList(),
                "PUT /setMetamodel" to listOf("envKey"),
                "PUT /setModel" to listOf("envKey"),
                "PUT /setEventDefinitions" to listOf("envKey"),
                "PUT /setStateMachine" to listOf("envKey", "attachedTo"),
                "POST /setTickDelay" to listOf("envKey", "milliseconds"),
                "POST /setActiveState" to listOf("envKey", "modelElement", "state"),
                "POST /setEventTtl" to listOf("envKey", "ttlMs"),
                "POST /registerObjectObserver" to listOf("envKey", "clientId", "objectId"),
                "POST /registerDomainListener" to listOf("envKey", "clientId", "domain"),
                "POST /registerClientForWebSocket" to listOf("clientId", "envKey"),
                "POST /runEnvironment" to listOf("envKey"),
                "POST /startEnvironment" to listOf("envKey"),
                "POST /stopEnvironment" to listOf("envKey"),
                "GET /health" to emptyList(),
                "GET /observatory/environments" to emptyList(),
                "GET /observatory/statemachine" to listOf("envKey", "modelElement"),
                "POST /observatory/registerClient" to listOf("clientId", "envKey", "modelElement"),
                "GET /observatory/model" to listOf("envKey"),
                "POST /observatory/subscribeModel" to listOf("envKey", "clientId"),
                "POST /observatory/unsubscribeModel" to listOf("envKey", "clientId"),
            ),
            RuntimeRoute.entries.map { "${it.verb} ${it.path}" to it.parameters },
        )
    }

    @Test
    fun `only the uploads take a body, each of its language`() {
        assertEquals(
            mapOf(
                RuntimeRoute.SET_METAMODEL to BodyKind.KMETA,
                RuntimeRoute.SET_MODEL to BodyKind.KMODEL,
                RuntimeRoute.SET_EVENT_DEFINITIONS to BodyKind.KMETA,
                RuntimeRoute.SET_STATE_MACHINE to BodyKind.KSTATES,
            ),
            RuntimeRoute.entries.filter { it.body != BodyKind.NONE }.associateWith { it.body },
        )
        assertEquals(RuntimeRoute.entries.filter { it.verb == HttpVerb.PUT }, RuntimeRoute.entries.filter { it.body != BodyKind.NONE })
    }

    @Test
    fun `answers and timeout classes follow the catalogue`() {
        assertEquals(
            mapOf(
                RuntimeRoute.CREATE_ENVIRONMENT to ResponseKind.ENVIRONMENT_KEY,
                RuntimeRoute.REGISTER_CLIENT_FOR_WEB_SOCKET to ResponseKind.ACCESS_KEY,
                RuntimeRoute.HEALTH to ResponseKind.HEALTH,
                RuntimeRoute.LIST_ENVIRONMENTS to ResponseKind.ENVIRONMENT_LIST,
                RuntimeRoute.STATE_MACHINE_SOURCE to ResponseKind.STATE_MACHINE_SOURCE,
                RuntimeRoute.REGISTER_OBSERVATORY_CLIENT to ResponseKind.ACCESS_KEY,
                RuntimeRoute.MODEL_SNAPSHOT to ResponseKind.MODEL_SNAPSHOT,
            ),
            RuntimeRoute.entries.filter { it.response != ResponseKind.EMPTY }.associateWith { it.response },
        )
        assertEquals(
            mapOf(
                TimeoutClass.QUICK to 15,
                TimeoutClass.UPLOAD to 4,
                TimeoutClass.STOP to 1,
                TimeoutClass.SNAPSHOT to 1,
            ),
            RuntimeRoute.entries.groupingBy { it.timeout }.eachCount(),
        )
        assertEquals(TimeoutClass.STOP, RuntimeRoute.STOP_ENVIRONMENT.timeout)
        assertEquals(TimeoutClass.SNAPSHOT, RuntimeRoute.MODEL_SNAPSHOT.timeout)
        assertEquals(TimeoutClass.UPLOAD, RuntimeRoute.SET_STATE_MACHINE.timeout)
    }

    @Test
    fun `every path is distinct and starts with a slash`() {
        assertEquals(RuntimeRoute.entries.size, RuntimeRoute.entries.map { it.path }.toSet().size)
        assertEquals(emptyList(), RuntimeRoute.entries.filterNot { it.path.startsWith("/") })
    }
}
