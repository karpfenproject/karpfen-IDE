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

import io.karpfen.dsl.instance.Model
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.fixtures.TestWorkspaces
import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.RuntimeClientOptions
import io.karpfen.ide.runtime.protocol.api.RuntimeTimeouts
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.conformance.DescriptorTiming
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.protocol.fake.FakeResponse
import io.karpfen.ide.runtime.protocol.fake.FakeRuntime
import io.karpfen.ide.runtime.protocol.fake.FakeWebSocketLink
import io.karpfen.ide.runtime.protocol.golden.FakeObject
import io.karpfen.ide.runtime.protocol.golden.FakeProperty
import io.karpfen.ide.runtime.protocol.golden.FakeScalar
import io.karpfen.ide.runtime.protocol.golden.RuntimeWireWriter
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import io.karpfen.ide.runtime.session.deploy.DeployTiming
import io.karpfen.ide.runtime.session.deploy.EnvironmentLedger
import io.karpfen.ide.runtime.session.fixtures.ModelWireText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.assertIs
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * The parts of an end-to-end test of sessions on the test's virtual time: a [FakeRuntime] that answers only what the
 * test programs, the real client over its transports, the real sessions with a ledger of their own, and workspaces with
 * the synthesized models and the single robot example. The answers a deployment needs are programmed by
 * [programDeployment]; pushes are written with the runtime's writers through [ModelWireText] and [RuntimeWireWriter].
 *
 * @property scope the test's scope.
 * @property root the folder the workspaces are copied into.
 * @param timing the waits and bounds of the deployments and the stops of the sessions.
 * @param timeouts the timeouts of the client.
 */
internal class SessionRig(
    private val scope: TestScope,
    private val root: Path,
    timing: DeployTiming = DeployTiming(),
    timeouts: RuntimeTimeouts = RuntimeTimeouts(),
) {
    /** The fake runtime; its follow-ups run in the test's background scope. */
    val fake = FakeRuntime(scope.backgroundScope)

    /** The one test dispatcher of the sessions and the client. */
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)

    /** The ledger of the sessions. */
    val ledger = EnvironmentLedger()

    /** The options of the sessions: virtual time and a seeded random, so the client ids are the same in every run. */
    val options = RuntimeSessionOptions(
        dispatcher = dispatcher,
        timeSource = scope.testScheduler.timeSource,
        random = Random(SEED),
        deployTiming = timing,
    )

    /** The sessions. */
    val sessions: RuntimeSessions = RuntimeSessions(options, ledger)

    /** The client of the fake, on virtual time. */
    val client: RuntimeClient = RuntimeClient(
        ENDPOINT,
        RuntimeClientOptions(dispatcher = dispatcher, timeSource = scope.testScheduler.timeSource, timeouts = timeouts),
        fake.transports(),
    )

    /** The workspaces of the test. */
    private val workspaces = TestWorkspaces(scope)

    /** Copies [model] with [timing] into a folder of its own, opens the workspace and answers both. */
    fun open(model: SynthesizedModel, timing: DescriptorTiming = DescriptorTiming.EVENT_SCENARIOS): Opened {
        val descriptor = SynthesizedModels.copyTo(root.resolve(model.folder), model, timing)
        return Opened(workspaces.open(root), descriptor)
    }

    /** Copies the single robot example into a folder of its own, opens the workspace and answers both. */
    fun openSingleRobot(): Opened {
        val descriptor = DescriptorExamples.copyExplicitContext(root.resolve("robot"))
        return Opened(workspaces.open(root), descriptor)
    }

    /** Prepares the deployment of [opened], failing the test if it is blocked. */
    suspend fun prepare(opened: Opened): PreparedDeployment =
        when (val preparation = sessions.prepare(opened.workspace, opened.descriptor)) {
            is Preparation.Ready -> preparation.prepared
            is Preparation.Blocked -> fail("the deployment is blocked: ${preparation.findings}")
        }

    /** The prepared deployment of [model]. */
    suspend fun prepared(model: SynthesizedModel): Deployment {
        val opened = open(model)
        return Deployment(prepare(opened), opened)
    }

    /** Launches a session of [deployment] in the test's background scope. */
    fun launch(deployment: Deployment, options: DeployOptions = DeployOptions()): RuntimeSession =
        sessions.launch(deployment.prepared, client, scope.backgroundScope, options)

    /**
     * Programs the answers a deployment of [deployment] to [environment] needs beyond the fake's defaults: the IDE
     * support check, the read-back after the activation, and, if the deployment starts, the snapshot that confirms the
     * start with [started] as every machine's stack.
     */
    fun programDeployment(
        deployment: Deployment,
        environment: String = FakeRuntime.environmentKey(1),
        start: Boolean = true,
        started: Map<String, List<String>> = deployment.initialStacks,
    ) {
        fake.respond(RuntimeRoute.MODEL_SNAPSHOT, missingEnvironmentKey())
        programSnapshot(deployment, environment, RuntimePhase.ACTIVATED, 0)
        if (start) programSnapshot(deployment, environment, RuntimePhase.RUNNING, started.size.toLong(), started)
    }

    /** Programs the next snapshot of [environment]: the model of [deployment] with [roots] at [phase] and [revision]. */
    fun programSnapshot(
        deployment: Deployment,
        environment: String,
        phase: RuntimePhase,
        revision: Long,
        activeStates: Map<String, List<String>> = emptyMap(),
        roots: List<String> = deployment.wire.rootsJson(),
    ) {
        fake.respond(
            RuntimeRoute.MODEL_SNAPSHOT,
            FakeResponse.json(RuntimeWireWriter.snapshot(environment, phase, revision, activeStates, roots)),
            where = mapOf(ENV_KEY to environment),
        )
    }

    /** Waits on virtual time until [session] is in a state [accept] accepts, and answers it. */
    suspend fun awaitState(session: RuntimeSession, accept: (SessionState) -> Boolean): SessionState =
        withTimeout(PATIENCE) { session.state.first(accept) }

    /** Launches [deployment] with its answers programmed and waits until the session runs. */
    suspend fun running(deployment: Deployment): RuntimeSession {
        programDeployment(deployment)
        val session = launch(deployment)
        assertIs<SessionState.Running>(awaitState(session) { it != SessionState.Deploying })
        return session
    }

    /** The one link of [session]'s client that is open. */
    fun linkOf(session: RuntimeSession): FakeWebSocketLink = fake.linksOf(session.clientId.value).last { it.isOpen }

    /** The push of the stack [stack] of [machine] at [revision] to [session] in [environment]. */
    fun stack(session: RuntimeSession, machine: String, stack: List<String>, revision: Long, environment: String = FakeRuntime.environmentKey(1)): String =
        RuntimeWireWriter.envelope(
            environment, session.clientId.value, WireNames.STATE_CHANGE, RuntimeWireWriter.stateChangePayload(machine, stack, revision),
        )

    /** The push of the changed [value] of the object [id] at [revision] to [session] in [environment]. */
    fun changed(
        session: RuntimeSession,
        id: String,
        value: String,
        revision: Long,
        environment: String = FakeRuntime.environmentKey(1),
    ): String =
        RuntimeWireWriter.envelope(
            environment, session.clientId.value, WireNames.OBJECT_CHANGED, RuntimeWireWriter.objectChangedPayload(id, revision, value),
        )

    /** The push of the deleted object [id] at [revision] to [session] in [environment]. */
    fun deleted(session: RuntimeSession, id: String, revision: Long, environment: String = FakeRuntime.environmentKey(1)): String =
        RuntimeWireWriter.envelope(
            environment, session.clientId.value, WireNames.OBJECT_DELETED, RuntimeWireWriter.objectDeletedPayload(id, revision),
        )

    /** The push of the domain event [event] of [domain] to [session] in [environment], injected over the WebSocket. */
    fun domainEvent(session: RuntimeSession, domain: String, event: String, environment: String = FakeRuntime.environmentKey(1)): String =
        RuntimeWireWriter.envelope(
            environment,
            session.clientId.value,
            WireNames.DOMAIN_EVENT,
            RuntimeWireWriter.domainEventPayload(domain, event, "", WireNames.FORMAT_NONE, "EXTERNAL_MESSAGE", EVENT_TIME, EVENT_TTL),
        )

    /**
     * A workspace with a model copied into it.
     *
     * @property workspace the opened workspace.
     * @property descriptor the descriptor of the model.
     */
    data class Opened(val workspace: Workspace, val descriptor: Path) {
        /** The analyzed model of the descriptor's context, as the workspace analyzed it last. */
        val model: Model
            get() = workspace.analysis.value.contexts.single { it.context.descriptor == descriptor.toAbsolutePath().normalize() }.models.values.single()
    }

    /**
     * A prepared deployment with its workspace.
     *
     * @property prepared what a session deploys.
     * @property opened the workspace it was prepared from.
     */
    class Deployment(val prepared: PreparedDeployment, val opened: Opened) {
        /** The runtime's JSON of the analyzed model. */
        val wire: ModelWireText = ModelWireText(opened.model)

        /** The predicted initial stack of every attached machine. */
        val initialStacks: Map<String, List<String>>
            get() = prepared.layouts.associate { it.attachedTo to it.initialStack }

        /** The object [id] of the model as the runtime holds it. */
        fun objectOf(id: String): FakeObject = checkNotNull(wire.objects[id]) { "the model has no object '$id'" }

        /** The runtime's JSON of the object [id] with the properties of [edits] set, nested objects as modeled. */
        fun json(id: String, vararg edits: FakeProperty): String {
            val edited = objectOf(id).let { original ->
                original.copy(properties = original.properties.filter { property -> edits.none { it.key == property.key } } + edits)
            }
            return RuntimeWireWriter.objectJson(edited, wire.objects::get)
        }
    }

    companion object {
        /** The runtime the client believes it talks to; the fake answers whatever the URL. */
        val ENDPOINT: RuntimeEndpoint = RuntimeEndpoint.parse("http://127.0.0.1:8080")

        /** The seed of the sessions' random. */
        const val SEED: Int = 7

        /** The query parameter of an environment's key. */
        const val ENV_KEY: String = "envKey"

        /** How long a test waits for a state, on virtual time. */
        val PATIENCE = 5.minutes

        /** The receipt time of the domain events the tests push. */
        const val EVENT_TIME: Long = 1_761_560_130_123L

        /** The time to live of the domain events the tests push. */
        const val EVENT_TTL: Long = 30_000L

        /** The string property [key] with [value]. */
        fun text(key: String, value: String): FakeProperty = FakeProperty.One(key, FakeScalar.Text(value))

        /** The list property [key] with the strings [values]. */
        fun texts(key: String, vararg values: String): FakeProperty = FakeProperty.Many(key, values.map { FakeScalar.Text(it) })

        /** The runtime's answer to the snapshot route without an environment key: the route exists. */
        fun missingEnvironmentKey(): FakeResponse =
            FakeResponse.rejected(FakeResponse.BAD_REQUEST, RuntimeText.MISSING_PARAMETER, mapOf("name" to ENV_KEY))
    }
}
