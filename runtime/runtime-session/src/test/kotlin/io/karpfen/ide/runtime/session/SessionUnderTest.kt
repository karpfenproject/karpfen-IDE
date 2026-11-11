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

import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.HttpVerb
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.conformance.DescriptorTiming
import io.karpfen.ide.runtime.protocol.conformance.Patience
import io.karpfen.ide.runtime.protocol.conformance.RuntimeScenarioScope
import io.karpfen.ide.runtime.protocol.conformance.RuntimeUnderTest
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.protocol.conformance.awaitValue
import io.karpfen.ide.runtime.protocol.conformance.eventually
import io.karpfen.ide.runtime.protocol.message.DecodedFrame
import io.karpfen.ide.runtime.protocol.message.JsonReading
import io.karpfen.ide.runtime.protocol.message.RuntimeFrameDecoder
import io.karpfen.ide.runtime.protocol.message.RuntimeJson
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.message.WireValue
import io.karpfen.ide.runtime.protocol.transport.ConnectOutcome
import io.karpfen.ide.runtime.protocol.transport.HttpOutcome
import io.karpfen.ide.runtime.protocol.transport.LinkFrame
import io.karpfen.ide.runtime.protocol.transport.LinkSendResult
import io.karpfen.ide.runtime.protocol.transport.TextRequest
import io.karpfen.ide.runtime.protocol.transport.WebSocketLink
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import io.karpfen.ide.runtime.session.events.InjectionOutcome
import io.karpfen.ide.runtime.session.events.InjectionRequest
import io.karpfen.ide.runtime.session.events.PayloadKind
import io.karpfen.ide.runtime.session.mirror.MirrorComparison
import io.karpfen.ide.runtime.session.mirror.MirrorReducer
import io.karpfen.ide.runtime.session.mirror.MirrorSnapshot
import io.karpfen.ide.runtime.session.observation.ObservedModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail
import kotlin.time.Duration

/**
 * The runtime the session scenarios deploy to. It is a [RuntimeUnderTest], usually the real runtime of the live task,
 * with what a session needs on top of it. Every scenario gets a [SessionScenario]. It has the sessions of the IDE on
 * the runtime's own dispatcher and clock, workspaces with real options, and the synthesized models copied into folders
 * of its own. Deployments upload saved files, so every scenario writes its models to disk and opens them as
 * workspaces. When a scenario asks for a deploy configuration, it is written into the descriptor.
 *
 * @param runtime the runtime the scenarios run against.
 * @param endpoint where it listens.
 */
class SessionUnderTest(private val runtime: RuntimeUnderTest, private val endpoint: RuntimeEndpoint) {
    /** How long the scenarios wait for the runtime. */
    val patience: Patience
        get() = runtime.patience

    /**
     * Runs [scenario] against the runtime with its models below [root]. At its end the sessions it launched are shut
     * down, so it leaves its environments stopped, and its workspaces are closed.
     */
    fun run(root: Path, scenario: suspend SessionScenario.() -> Unit) {
        runtime.run {
            val session = SessionScenario(this, endpoint, root)
            try {
                session.scenario()
            } finally {
                withContext(NonCancellable) { session.finish() }
            }
        }
    }
}

/**
 * What a session scenario has while it runs. That is the scope of the runtime under test, a client and the sessions of
 * the IDE, its models on disk, and the waits and reads the scenarios share. Failure messages name the runtime, its run
 * folder and every session with its environment, client id, state, stacks and mirror.
 *
 * @param scope the scope of the runtime under test.
 * @property endpoint where the runtime listens.
 * @param root the folder the models are copied into.
 */
class SessionScenario(private val scope: RuntimeScenarioScope, val endpoint: RuntimeEndpoint, private val root: Path) : RuntimeScenarioScope by scope {
    /** Keeps the frames the sessions receive. */
    private val tap = FrameTap(this)

    /**
     * The client of the runtime, over the scenario's transports with their links tapped. The runtime under test closes
     * the transports.
     */
    val client: RuntimeClient = RuntimeClient(endpoint, scope.clientOptions, tap.tapping(scope.transports))

    /** The sessions of the IDE, on the runtime's dispatcher and clock. */
    val sessions: RuntimeSessions = RuntimeSessions(
        RuntimeSessionOptions(dispatcher = scope.clientOptions.dispatcher, timeSource = scope.clientOptions.timeSource, random = Random.Default),
    )

    /** The sessions the scenario launched. */
    private val launched = CopyOnWriteArrayList<RuntimeSession>()

    /** The workspaces the scenario opened. */
    private val workspaces = CopyOnWriteArrayList<Workspace>()

    /** Numbers the folders of the copied models. */
    private val folders = AtomicInteger()

    /** The runtime and every session launched so far with its environment, client id, state, stacks and mirror. */
    override val diagnostics: String
        get() = scope.diagnostics + launched.joinToString(prefix = " Sessions: ", separator = "; ") { session ->
            val model = session.model.value
            "${session.environmentOrNull} as ${session.clientId} ${session.state.value}, stacks ${model.stacks}, sync " +
                "${model.sync}, mirror ${model.mirror.objects.values.map { it.id to it.properties.map { p -> p.key to p.value } }}"
        }

    /** Copies [model] with [timing] in its descriptor into a folder of its own and returns the descriptor. */
    fun copy(model: SynthesizedModel, timing: DescriptorTiming = DescriptorTiming.EVENT_SCENARIOS): Path =
        SynthesizedModels.copyTo(root.resolve("${model.folder}-${folders.incrementAndGet()}"), model, timing)

    /** Opens the folder of [descriptor] as a workspace with real options, without watching. */
    fun open(descriptor: Path): Workspace =
        Workspace(descriptor.parent, this, WorkspaceOptions(watch = false)).also { workspaces += it }

    /** Prepares the deployment of [descriptor] in [workspace]. It fails the scenario if the deployment is blocked. */
    suspend fun prepare(workspace: Workspace, descriptor: Path): PreparedDeployment =
        when (val preparation = sessions.prepare(workspace, descriptor)) {
            is Preparation.Ready -> preparation.prepared
            is Preparation.Blocked -> fail("The deployment of $descriptor is blocked: ${preparation.findings}. $diagnostics")
        }

    /** Launches a session of [prepared] in the scenario's scope as [options] say. */
    fun launch(prepared: PreparedDeployment, options: DeployOptions = DeployOptions()): RuntimeSession =
        sessions.launch(prepared, client, this, options).also { launched += it }

    /**
     * Deploys [prepared] as [options] say and waits, at most [within], until the deployment ended. Then the session
     * runs, or it is ready when the options say not to start. A failed deployment fails the scenario.
     */
    suspend fun deploy(prepared: PreparedDeployment, options: DeployOptions = DeployOptions(), within: Duration = patience.deploy): RuntimeSession {
        val session = launch(prepared, options)
        val state = awaitValue("the end of the deployment of ${prepared.plan.name}", session.state, within) { it != SessionState.Deploying }
        when {
            state is SessionState.Failed -> fail("The deployment of ${prepared.plan.name} failed: ${state.problem}. $diagnostics")
            options.start -> assertIs<SessionState.Running>(state, diagnostics)
            else -> assertIs<SessionState.Ready>(state, diagnostics)
        }
        return session
    }

    /** Copies, prepares and deploys [model] with [timing] as [options] say. */
    suspend fun deploy(
        model: SynthesizedModel,
        timing: DescriptorTiming = DescriptorTiming.EVENT_SCENARIOS,
        options: DeployOptions = DeployOptions(),
        within: Duration = patience.deploy,
    ): RuntimeSession {
        val descriptor = copy(model, timing)
        return deploy(prepare(open(descriptor), descriptor), options, within)
    }

    /** Copies, prepares and deploys the single robot example as [options] say. */
    suspend fun deploySingleRobot(options: DeployOptions = DeployOptions()): RuntimeSession {
        val descriptor = DescriptorExamples.copyExplicitContext(root.resolve("robot-${folders.incrementAndGet()}"))
        return deploy(prepare(open(descriptor), descriptor), options)
    }

    /** Waits until the observed model of this session is one that [accept] accepts, and returns it. */
    suspend fun RuntimeSession.awaitModel(what: String, within: Duration = patience.reaction, accept: (ObservedModel) -> Boolean): ObservedModel =
        awaitValue("$what in ${environmentOrNull}", model, within, accept)

    /** Waits until the machine attached to [machine] shows [stack] in this session's model. */
    suspend fun RuntimeSession.awaitStack(machine: String, stack: List<String>, within: Duration = patience.reaction): ObservedModel =
        awaitModel("the stack $stack of $machine", within) { it.stacks[machine] == stack }

    /**
     * Injects the event [name] of [domain] with the payload [text] of [kind] and returns the frame. It fails unless the
     * frame was sent.
     */
    suspend fun RuntimeSession.inject(domain: String, name: String, kind: PayloadKind = PayloadKind.NONE, text: String = ""): String {
        val outcome = events.inject(InjectionRequest(domain, name, kind, text))
        return assertIs<InjectionOutcome.Sent>(outcome, "injecting $name of $domain: $outcome. $diagnostics").frameText
    }

    /** Pulls this session's model and returns it. It fails unless the pull is done. */
    suspend fun RuntimeSession.pulled(): ObservedModel {
        assertEquals(SessionCommandOutcome.Done, pull(), "a pull of $environmentOrNull. $diagnostics")
        return model.value
    }

    /**
     * Reads a snapshot of this session's environment with a raw request, as the runtime writes it. The roots are read
     * like the seed.
     */
    suspend fun RuntimeSession.snapshot(): RawSnapshot = snapshotOf(environment, prepared)

    /** Reads a snapshot of [environment] with a raw request and reads its roots with the schema of [prepared]. */
    suspend fun snapshotOf(environment: EnvironmentKey, prepared: PreparedDeployment): RawSnapshot {
        val answer = call(RuntimeRoute.MODEL_SNAPSHOT, "envKey" to environment.value)
        assertEquals(OK, answer.status, "the snapshot of $environment: ${answer.body}. $diagnostics")
        val body = assertIs<WireValue.WireObject>((RuntimeJson.read(answer.body) as JsonReading.Read).value)
        val revision = (body["revision"] as WireValue.WireNumber).value.toLong()
        val stacks = (body["activeStates"] as WireValue.WireObject).fields.associate { field ->
            field.name to (field.value as WireValue.WireArray).items.map { (it as WireValue.WireString).value }
        }
        val roots = (body["roots"] as WireValue.WireArray).items.map { it as WireValue.WireObject }
        val mirror = MirrorReducer(prepared.schema, prepared.plan.modelObjectIds).replace(roots, revision).snapshot
        return RawSnapshot((body["phase"] as WireValue.WireString).value, revision, stacks, mirror)
    }

    /**
     * Sends [route] with the query [parameters] in the order given and with [body] for a PUT. It fails on a transport
     * failure.
     */
    suspend fun call(route: RuntimeRoute, vararg parameters: Pair<String, String>, body: String = ""): HttpOutcome.Answered {
        val request = TextRequest(route.verb, route.path, parameters.toList(), body.takeIf { route.verb == HttpVerb.PUT })
        return when (val outcome = transports.http.exchange(request)) {
            is HttpOutcome.Answered -> outcome
            is HttpOutcome.Failed -> fail("$route failed on the transport: ${outcome.failure}. $diagnostics")
        }
    }

    /** Every push this session received on its links so far, applied or not, in arrival order. */
    fun RuntimeSession.received(): List<RuntimePush> = tap.pushes(environment, clientId)

    /**
     * Connects a second client of its own, named `ide-live-<random>`, to [environment] with raw requests and a raw
     * link. It subscribes the client to the whole model once the runtime has its session.
     */
    suspend fun rawObserver(environment: EnvironmentKey): RawObserver {
        val clientId = ClientId("ide-live-" + Random.nextLong(Long.MAX_VALUE).toString(HEX))
        val key = call(RuntimeRoute.REGISTER_CLIENT_FOR_WEB_SOCKET, "clientId" to clientId.value, "envKey" to environment.value)
        assertEquals(OK, key.status, "a key for $clientId. $diagnostics")
        val link = assertIs<ConnectOutcome.Connected>(transports.webSockets.connect(endpoint.webSocketUrl), diagnostics).link
        val observer = RawObserver(environment, clientId, link, this)
        assertEquals(LinkSendResult.Sent, link.send(listOf(clientId.value, environment.value, key.body).joinToString(WireNames.HANDSHAKE_SEPARATOR)))
        eventually("the model subscription of $clientId") {
            call(RuntimeRoute.SUBSCRIBE_MODEL, "envKey" to environment.value, "clientId" to clientId.value).takeIf { it.status == OK }
        }
        return observer
    }

    /** Fails unless this session's mirror equals [snapshot]'s, object by object. */
    fun RuntimeSession.assertMirrorEquals(snapshot: RawSnapshot) {
        assertEquals(emptyList(), MirrorComparison.differences(snapshot.mirror, model.value.mirror), "the mirror of $environmentOrNull. $diagnostics")
    }

    /** Shuts every launched session down, which stops its environment, and closes the workspaces. */
    suspend fun finish() {
        launched.forEach { it.shutdown() }
        workspaces.forEach { it.close() }
    }

    private companion object {
        /** The status of a success. */
        const val OK = 200

        /** The radix of the random part of a raw client's id. */
        const val HEX = 16
    }
}

/**
 * A second client of an environment, connected and subscribed with raw requests and a raw link, that keeps every push
 * it receives.
 *
 * @property environment the environment.
 * @property clientId the client's id.
 * @param link its link.
 * @param scope where its reader runs.
 */
class RawObserver(val environment: EnvironmentKey, val clientId: ClientId, private val link: WebSocketLink, scope: CoroutineScope) {
    /** The texts received, guarded by itself. */
    private val texts = ArrayList<String>()

    init {
        scope.launch {
            for (frame in link.incoming) if (frame is LinkFrame.Text) synchronized(texts) { texts += frame.text }
        }
    }

    /** Every push received so far, in arrival order. */
    fun pushes(): List<RuntimePush> {
        val decoder = RuntimeFrameDecoder(environment, clientId)
        return synchronized(texts) { texts.toList() }.mapNotNull { (decoder.decode(it) as? DecodedFrame.Push)?.push }
    }

    /** Closes the link. */
    suspend fun close() {
        link.close(WireNames.CLOSE_NORMAL, "The scenario is done")
    }
}

/**
 * A snapshot of an environment as a raw request read it.
 *
 * @property phase the phase the runtime reported.
 * @property revision its revision.
 * @property stacks the active stack of every machine that reported one.
 * @property mirror its roots, read as the session reads them.
 */
data class RawSnapshot(val phase: String, val revision: Long, val stacks: Map<String, List<String>>, val mirror: MirrorSnapshot)

/** The environment of this session, which every session has once its deployment created it. */
val RuntimeSession.environment: EnvironmentKey
    get() = checkNotNull(progress.value.environment) { "the session has no environment yet" }

/** The environment of this session, or null before the deployment created it. */
val RuntimeSession.environmentOrNull: EnvironmentKey?
    get() = progress.value.environment
