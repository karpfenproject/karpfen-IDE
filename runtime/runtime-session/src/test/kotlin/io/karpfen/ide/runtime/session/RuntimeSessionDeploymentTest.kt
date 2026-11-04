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
package io.karpfen.ide.runtime.session

import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import io.karpfen.ide.runtime.session.deploy.DeployStepKind
import io.karpfen.ide.runtime.session.deploy.StepStatus
import io.karpfen.ide.runtime.session.events.InjectionAvailability
import io.karpfen.ide.runtime.session.observation.ObservationMode
import io.karpfen.ide.runtime.session.observation.SyncStatus
import io.karpfen.ide.runtime.session.states.StackOrigin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers deploying through a session end to end against the fake runtime, which answers only what each test programs:
 * the requests of a deployment of the lamp and of the single robot arrive in the order of the deployment's steps, with
 * the deploy configuration and a listener per domain; the progress reaches its end; the session runs with the reported
 * stacks in place of the predictions, observation on and events injectable; and a deployment without start or without
 * observation ends ready or with a pulled model.
 */
class RuntimeSessionDeploymentTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `deploying the lamp sends the requests of the deployment's steps in order`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)

        val session = rig.running(deployment)

        assertEquals(
            listOf(
                RuntimeRoute.HEALTH, RuntimeRoute.MODEL_SNAPSHOT, RuntimeRoute.CREATE_ENVIRONMENT,
                RuntimeRoute.REGISTER_CLIENT_FOR_WEB_SOCKET, RuntimeRoute.SET_METAMODEL, RuntimeRoute.SET_EVENT_DEFINITIONS,
                RuntimeRoute.SET_MODEL, RuntimeRoute.SET_STATE_MACHINE, RuntimeRoute.SET_STATE_MACHINE, RuntimeRoute.SET_TICK_DELAY,
                RuntimeRoute.SET_EVENT_TTL, RuntimeRoute.REGISTER_DOMAIN_LISTENER, RuntimeRoute.REGISTER_DOMAIN_LISTENER,
                RuntimeRoute.RUN_ENVIRONMENT, RuntimeRoute.SUBSCRIBE_MODEL, RuntimeRoute.MODEL_SNAPSHOT,
                RuntimeRoute.START_ENVIRONMENT, RuntimeRoute.MODEL_SNAPSHOT,
            ),
            rig.fake.requests.map { it.route },
        )
        assertEquals(listOf("lamp", "wallSwitch"), rig.fake.requests(RuntimeRoute.SET_STATE_MACHINE).map { it.parameter("attachedTo") })
        assertEquals(listOf("50"), rig.fake.requests(RuntimeRoute.SET_TICK_DELAY).map { it.parameter("milliseconds") })
        assertEquals(listOf("30000"), rig.fake.requests(RuntimeRoute.SET_EVENT_TTL).map { it.parameter("ttlMs") })
        assertEquals(listOf("service", "switch"), rig.fake.requests(RuntimeRoute.REGISTER_DOMAIN_LISTENER).map { it.parameter("domain") })
        assertTrue(
            rig.fake.requests(RuntimeRoute.REGISTER_DOMAIN_LISTENER).all { it.parameter("clientId") == session.clientId.value },
            "the listeners belong to the session's client id",
        )
        assertEquals(listOf(deployment.prepared.plan.model.snapshot.text), rig.fake.requests(RuntimeRoute.SET_MODEL).map { it.body })
        assertTrue(rig.fake.handshakes.single().startsWith("${session.clientId.value}:env-1:"), rig.fake.handshakes.toString())
        session.close()
    }

    @Test
    fun `a deployed lamp runs, observed live, with the reported stacks and events injectable`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)

        val session = rig.running(deployment)

        val progress = session.progress.value
        assertEquals(progress.total, progress.completed, "every step done or skipped: $progress")
        assertTrue(progress.steps.none { it.status == StepStatus.FAILED || it.status == StepStatus.PENDING }, "$progress")
        assertEquals("env-1", progress.environment?.value)
        val model = session.model.value
        assertEquals(ObservationMode.ON, model.mode)
        assertEquals(SyncStatus.Live(2), model.sync)
        assertEquals(RuntimePhase.RUNNING, model.phase)
        assertEquals(mapOf("lamp" to listOf("off"), "wallSwitch" to listOf("down")), model.states.machines.mapValues { it.value.stack })
        assertTrue(model.states.machines.values.all { it.origin == StackOrigin.REPORTED }, "${model.states}")
        assertEquals(ConnectionState.Open(1), session.connection.value)
        assertEquals(InjectionAvailability.Live, session.events.availability.value)
        assertEquals(deployment.prepared.plan.findings, session.problems.value, "a deployment as analyzed adds no problem")
        session.close()
    }

    @Test
    fun `a deployment without start ends ready, and one without observation reads the model back once`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        rig.programDeployment(deployment, start = false)

        val session = rig.launch(deployment, DeployOptions(start = false, observe = false))

        assertEquals("env-1", assertIs<SessionState.Ready>(rig.awaitState(session) { it != SessionState.Deploying }).environment.value)
        assertEquals(emptyList(), rig.fake.requests(RuntimeRoute.SUBSCRIBE_MODEL), "no subscription without observation")
        assertEquals(emptyList(), rig.fake.requests(RuntimeRoute.START_ENVIRONMENT))
        assertEquals(StepStatus.SKIPPED, session.progress.value.steps.single { it.kind == DeployStepKind.START }.status)
        assertEquals(SyncStatus.Pulled(0), session.model.value.sync)
        assertEquals(ObservationMode.OFF, session.model.value.mode)
        assertEquals(InjectionAvailability.Queued, session.events.availability.value)
        session.close()
    }

    @Test
    fun `the single robot deploys with one listener for its domain and runs with its initial state`() = runTest {
        val rig = SessionRig(this, root)
        val opened = rig.openSingleRobot()
        val deployment = SessionRig.Deployment(rig.prepare(opened), opened)

        val session = rig.running(deployment)

        assertEquals(listOf("public"), rig.fake.requests(RuntimeRoute.REGISTER_DOMAIN_LISTENER).map { it.parameter("domain") })
        assertEquals(listOf("turtle"), rig.fake.requests(RuntimeRoute.SET_STATE_MACHINE).map { it.parameter("attachedTo") })
        assertEquals(mapOf("turtle" to listOf("ready")), session.model.value.states.machines.mapValues { it.value.stack })
        assertTrue(session.problems.value.none { it.code == "deploy-model-differs" }, "${session.problems.value}")
        session.close()
    }
}
