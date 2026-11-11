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
package io.karpfen.ide.runtime.protocol.connection

import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.api.RuntimeResult
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.conformance.RuntimeScenarioScope
import io.karpfen.ide.runtime.protocol.conformance.RuntimeUnderTest
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.protocol.conformance.eventually
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.message.WireValue
import io.karpfen.ide.runtime.protocol.transport.ConnectOutcome
import io.karpfen.ide.runtime.protocol.transport.LinkClosure
import io.karpfen.ide.runtime.protocol.transport.LinkFrame
import io.karpfen.ide.runtime.protocol.transport.LinkSendResult
import io.karpfen.ide.runtime.protocol.transport.RuntimeTransports
import io.karpfen.ide.runtime.protocol.transport.WebSocketConnector
import io.karpfen.ide.runtime.protocol.transport.WebSocketLink
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Runs [body] as a scenario against this runtime with a real client of [endpoint], and stops every environment the
 * scenario deployed when it ends, also when it fails, so that a failed scenario leaves no engine running for the
 * scenarios after it.
 */
internal fun RuntimeUnderTest.runClient(endpoint: RuntimeEndpoint, body: suspend ScenarioClient.() -> Unit) {
    run {
        val client = ScenarioClient(this, endpoint)
        try {
            client.body()
        } finally {
            withContext(NonCancellable) { client.stopDeployed() }
        }
    }
}

/**
 * The real client of a connection scenario. It is a [RuntimeClient] over the scenario's transports. Its WebSocket seam
 * keeps every link the client opens, so a scenario can close one or read how it closed. It also offers the steps the
 * scenarios share. These are deploying the lamp through an environment handle, opening an observed connection and
 * subscribing it to the model. It keeps the handles it deployed, so that [stopDeployed] can stop them after the
 * scenario.
 *
 * @property scope the scenario's scope.
 * @param endpoint where the runtime listens.
 */
internal class ScenarioClient(val scope: RuntimeScenarioScope, endpoint: RuntimeEndpoint) {

    /** The WebSocket seam that keeps the links. */
    val links: KeptLinks = KeptLinks(scope.transports.webSockets)

    /** The client. */
    val client: RuntimeClient = RuntimeClient(endpoint, scope.clientOptions, RuntimeTransports(scope.transports.http, links))

    /** The handles of the environments deployed so far, in order. */
    private val deployed = MutableStateFlow<List<RuntimeEnvironment>>(emptyList())

    /**
     * Creates an environment and uploads the lamp with a tick delay of 50 ms and an event time to live of 30 000 ms.
     */
    suspend fun deployLamp(): RuntimeEnvironment {
        val environment = assertIs<RuntimeResult.Ok<RuntimeEnvironment>>(client.createEnvironment()).value
        deployed.update { it + environment }
        val files = SynthesizedModels.files(SynthesizedModel.LAMP)
        fun text(name: String): String = SynthesizedModels.text(SynthesizedModel.LAMP, name)
        done(environment.setMetamodel(text(files.metamodel)))
        done(environment.setEventDefinitions(text(checkNotNull(files.events))))
        done(environment.setModel(text(files.model)))
        files.machines.forEach { done(environment.setStateMachine(it.attachedTo, text(it.file))) }
        done(environment.setTickDelay(TICK_DELAY_MS))
        done(environment.setEventTtl(EVENT_TTL_MS))
        return environment
    }

    /** A fresh client id of the scenario: `ide-live-` and random hexadecimal digits. */
    fun clientId(): ClientId = ClientId("ide-live-" + Random.nextLong().toULong().toString(HEX_RADIX))

    /** A connection of [clientId] to [environment], with its keys from [issuer] or the handle, opened and observed. */
    suspend fun connect(
        environment: RuntimeEnvironment,
        clientId: ClientId = clientId(),
        issuer: AccessKeyIssuer? = null,
    ): ObservedConnection {
        val connection = ObservedConnection(client.connection(environment, clientId, scope, issuer), scope)
        connection.connection.open()
        return connection
    }

    /** Subscribes [clientId] to the model of [environment], repeated while its session is not attached yet. */
    suspend fun subscribe(environment: RuntimeEnvironment, clientId: ClientId) {
        scope.eventually("an attached session of $clientId") { done(environment.subscribeModel(clientId)) }
    }

    /**
     * Stops every environment deployed so far, whatever comes of it. This is the cleanup after a scenario, also after
     * one that failed. A handle bounds its stop and sends nothing for an environment that is stopped already or was
     * never activated.
     */
    suspend fun stopDeployed() {
        deployed.value.forEach { it.stop() }
    }

    /** Deploys the lamp, connects and subscribes a client before the start, starts and waits for the first stacks. */
    suspend fun runningLamp(): Pair<RuntimeEnvironment, ObservedConnection> {
        val environment = deployLamp()
        val connection = connect(environment)
        subscribe(environment, connection.connection.clientId)
        done(environment.activate())
        done(environment.start())
        connection.awaitStack("lamp", listOf("off"))
        connection.awaitStack("wallSwitch", listOf("down"))
        return environment to connection
    }

    private companion object {
        /** The tick delay of the scenarios. */
        const val TICK_DELAY_MS = 50

        /** The event time to live of the scenarios that inject events. */
        const val EVENT_TTL_MS = 30_000L

        /** The radix of the random part of a client id. */
        const val HEX_RADIX = 16

        /** The value of a call that succeeded, failing the scenario with the outcome otherwise. */
        fun <T> done(outcome: CallOutcome<T>): T = assertIs<CallOutcome.Done<T>>(outcome, "$outcome").value
    }
}

/**
 * A connection with the events it emitted so far, collected by a coroutine of the scenario.
 *
 * @property connection the connection.
 */
internal class ObservedConnection(val connection: RuntimeConnection, private val scope: RuntimeScenarioScope) {

    /** The events so far. */
    private val collected = MutableStateFlow<List<ConnectionEvent>>(emptyList())

    /** The events so far, in order. */
    val events: StateFlow<List<ConnectionEvent>> get() = collected

    init {
        scope.launch { connection.events.collect { collected.value = collected.value + it } }
    }

    /** The pushes received so far, in order. */
    val pushes: List<RuntimePush> get() = collected.value.filterIsInstance<ConnectionEvent.Received>().map { it.push }

    /** The stacks pushed for the machine attached to [modelElement] after the [epoch]th open, in order. */
    fun stacks(modelElement: String, epoch: Int = 1): List<List<String>> =
        afterOpen(epoch).filterIsInstance<RuntimePush.StateChanged>().filter { it.modelElementId == modelElement }.map { it.stack }

    /** The values pushed for the object [objectId] after the [epoch]th open, in order. */
    fun values(objectId: String, epoch: Int = 1): List<WireValue.WireObject> =
        afterOpen(epoch).filterIsInstance<RuntimePush.ObjectChanged>().filter { it.objectId == objectId }.map { it.value }

    /** Waits until the machine attached to [modelElement] reported [stack] after the [epoch]th open. */
    suspend fun awaitStack(modelElement: String, stack: List<String>, epoch: Int = 1) {
        scope.eventually("the stack $stack of $modelElement on the link of epoch $epoch") {
            stacks(modelElement, epoch).lastOrNull()?.takeIf { it == stack }
        }
    }

    /** The pushes received after the event `Opened(epoch)`. */
    private fun afterOpen(epoch: Int): List<RuntimePush> =
        collected.value.dropWhile { it != ConnectionEvent.Opened(epoch) }.filterIsInstance<ConnectionEvent.Received>().map { it.push }
}

/**
 * A WebSocket seam that keeps every link it opens and the texts the client sent on each, around [connector].
 */
internal class KeptLinks(private val connector: WebSocketConnector) : WebSocketConnector {

    /** The links opened so far. */
    private val kept = MutableStateFlow<List<KeptLink>>(emptyList())

    /** The links opened so far, in order. */
    val opened: List<KeptLink> get() = kept.value

    override suspend fun connect(url: String): ConnectOutcome =
        when (val outcome = connector.connect(url)) {
            is ConnectOutcome.Connected -> {
                val link = KeptLink(outcome.link)
                synchronized(this) { kept.value = kept.value + link }
                ConnectOutcome.Connected(link)
            }
            is ConnectOutcome.Failed -> outcome
        }
}

/**
 * A link that keeps the texts the client sent on [link], the handshake first.
 *
 * @property link the link.
 */
internal class KeptLink(private val link: WebSocketLink) : WebSocketLink {

    /** The texts the client sent. */
    private val texts = MutableStateFlow<List<String>>(emptyList())

    /** The texts the client sent, in order. */
    val sent: List<String> get() = texts.value

    override val incoming: ReceiveChannel<LinkFrame> get() = link.incoming

    override val closure: Deferred<LinkClosure> get() = link.closure

    override suspend fun send(text: String): LinkSendResult =
        link.send(text).also { if (it == LinkSendResult.Sent) synchronized(this) { texts.value = texts.value + text } }

    override suspend fun close(code: Int, reason: String) {
        link.close(code, reason)
    }
}

/** Fails unless [closure] is the close [code] with [reason] by the runtime. */
internal fun assertRuntimeClosed(code: Int, reason: String, closure: LinkClosure) {
    assertEquals(LinkClosure(code, reason, byClient = false), closure)
}
