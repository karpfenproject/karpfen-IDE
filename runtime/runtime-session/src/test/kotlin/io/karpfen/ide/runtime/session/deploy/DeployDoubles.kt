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
package io.karpfen.ide.runtime.session.deploy

import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.api.ActiveEnvironment
import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.api.RuntimeResult
import io.karpfen.ide.runtime.protocol.catalogue.AccessKey
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.connection.AccessKeyIssuer
import io.karpfen.ide.runtime.protocol.connection.ConnectionEnd
import io.karpfen.ide.runtime.protocol.connection.ConnectionEvent
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.connection.RuntimeConnection
import io.karpfen.ide.runtime.protocol.connection.SendOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleRefusal
import io.karpfen.ide.runtime.protocol.lifecycle.Observatory
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.protocol.lifecycle.Uploads
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.session.problem.DeployProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/** The runtime the doubles stand for. */
internal val ENDPOINT: RuntimeEndpoint = RuntimeEndpoint.parse("http://127.0.0.1:8080")

/** The key of the environment the client double creates. */
internal val ENVIRONMENT: EnvironmentKey = EnvironmentKey("env-1761560123456")

/** The client id the deployments of the tests connect as. */
internal val CLIENT: ClientId = ClientId("karpfen-ide-4f2c9a1b7e03")

/**
 * What the doubles of one deployment answer and what they received. Every call of a double is recorded in [log] by
 * its name, with its arguments in parentheses where it has any, and succeeds unless the test failed or held it; the
 * listener records what it hears in the same log, so the order of calls and reports shows. Confined to the test's
 * thread.
 *
 * @param clock the virtual time of the test in milliseconds.
 */
internal class DeployScript(private val clock: () -> Long) {
    /** The calls and reports, in order. */
    val log: MutableList<String> = mutableListOf()

    /** The failure each failing call answers every time, by the call's name. */
    private val failures = mutableMapOf<String, RuntimeFailure>()

    /** The phase a failing call leaves the environment in, by the call's name, where the test chose one. */
    private val phasesAfterFailure = mutableMapOf<String, EnvironmentPhase>()

    /** The names of the calls that suspend until the deployment is cancelled. */
    private val held = mutableSetOf<String>()

    /** What opening the connection answers. */
    var openAnswer: ConnectionState = ConnectionState.Open(1)

    /**
     * What the waits for an open connection answer, in turn: a state, or null for a connection that stays reconnecting
     * until the wait passes. Once used up, a wait answers the connection's state.
     */
    val awaitOpenAnswers: ArrayDeque<ConnectionState?> = ArrayDeque()

    /** What the reads of the model answer, in turn; once used up, the model with every attached machine in a state. */
    val pullAnswers: ArrayDeque<CallOutcome<RuntimeModelSnapshot>> = ArrayDeque()

    /** The virtual times of the reads of the model, in milliseconds. */
    val pullTimes: MutableList<Long> = mutableListOf()

    /** What comparing the model read back answers. */
    var differences: List<DeployProblem> = emptyList()

    /** The environment, once created. */
    var environment: ScriptedEnvironment? = null

    /** Fails every call named [call] with [failure], leaving the environment in [phaseAfter] if given. */
    fun fail(call: String, failure: RuntimeFailure, phaseAfter: EnvironmentPhase? = null) {
        failures[call] = failure
        phaseAfter?.let { phasesAfterFailure[call] = it }
    }

    /** Lets every call named [call] suspend until the deployment is cancelled. */
    fun hold(call: String) {
        held += call
    }

    /** The calls recorded after the last one named [call]. */
    fun callsAfter(call: String): List<String> = log.drop(log.indexOfLast { it == call || it.startsWith("$call(") } + 1)

    /** The calls recorded with the name [call]. */
    fun callsOf(call: String): List<String> = log.filter { it == call || it.startsWith("$call(") }

    /** The virtual time now, in milliseconds. */
    fun now(): Long = clock()

    /**
     * Records [call] with [arguments], suspends until cancelled if the call is held, and answers the failure the call
     * fails with, or null if it succeeds.
     */
    suspend fun enter(call: String, vararg arguments: Any): RuntimeFailure? {
        log += if (arguments.isEmpty()) call else "$call(${arguments.joinToString()})"
        if (call in held) awaitCancellation()
        return failures[call]
    }

    /** The phase the failing [call] leaves the environment in, if the test chose one. */
    fun phaseAfterFailure(call: String): EnvironmentPhase? = phasesAfterFailure[call]
}

/**
 * A double of the runtime client: checks and creations answer as scripted, and connections are doubles too.
 *
 * @param script what it answers and where it records.
 */
internal class ScriptedClient(private val script: DeployScript) : RuntimeClient {
    override val endpoint: RuntimeEndpoint = ENDPOINT

    override suspend fun health(): RuntimeResult<Unit> = result("health") { }

    override suspend fun checkIdeSupport(): RuntimeResult<Unit> = result("checkIdeSupport") { }

    override suspend fun activeEnvironments(): RuntimeResult<List<ActiveEnvironment>> = result("activeEnvironments") { emptyList() }

    override suspend fun createEnvironment(): RuntimeResult<RuntimeEnvironment> =
        result("createEnvironment") { ScriptedEnvironment(ENVIRONMENT, script).also { script.environment = it } }

    override fun connection(
        environment: RuntimeEnvironment,
        clientId: ClientId,
        scope: CoroutineScope,
        issuer: AccessKeyIssuer?,
    ): RuntimeConnection {
        script.log += "connection($clientId)"
        return ScriptedConnection(environment.key, clientId, script)
    }

    override fun close() {
        script.log += "closeClient"
    }

    /** The scripted result of [call], with [value] on success. */
    private suspend fun <T> result(call: String, value: () -> T): RuntimeResult<T> =
        script.enter(call)?.let { RuntimeResult.Failed(it) } ?: RuntimeResult.Ok(value())
}

/**
 * A double of an environment handle: calls answer as scripted and move the phase as the real handle would on success;
 * a failing call leaves the phase where the test chose, loses the environment on an unknown-environment rejection and
 * otherwise keeps it. A held activation, start or stop completes when cancelled, as the real handle's do.
 *
 * @property key the environment's key.
 * @param script what it answers and where it records.
 */
internal class ScriptedEnvironment(override val key: EnvironmentKey, private val script: DeployScript) : RuntimeEnvironment {
    /** The phase, written by the calls and by [settle]. */
    private val current = MutableStateFlow<EnvironmentPhase>(EnvironmentPhase.Configuring(Uploads.NONE))

    override val phase: StateFlow<EnvironmentPhase> = current.asStateFlow()

    /** The keys state machines were attached to, in order. */
    val attached: MutableList<String> = mutableListOf()

    /** Moves the phase to [phase], as a snapshot read through the real handle would. */
    fun settle(phase: EnvironmentPhase) {
        current.value = phase
    }

    override fun allows(operation: LifecycleOperation): LifecycleRefusal? = null

    override suspend fun setMetamodel(text: String): CallOutcome<Unit> = outcome("setMetamodel", text) { }

    override suspend fun setEventDefinitions(text: String): CallOutcome<Unit> = outcome("setEventDefinitions", text) { }

    override suspend fun setModel(text: String): CallOutcome<Unit> = outcome("setModel", text) { }

    override suspend fun setStateMachine(attachedTo: String, text: String): CallOutcome<Unit> =
        outcome("setStateMachine", attachedTo, text) { attached += attachedTo }

    override suspend fun setTickDelay(milliseconds: Int): CallOutcome<Unit> = outcome("setTickDelay", milliseconds) { }

    override suspend fun setEventTtl(milliseconds: Long): CallOutcome<Unit> = outcome("setEventTtl", milliseconds) { }

    override suspend fun registerObjectObserver(client: ClientId, objectId: String): CallOutcome<Unit> =
        outcome("registerObjectObserver", client, objectId) { }

    override suspend fun registerDomainListener(client: ClientId, domain: String): CallOutcome<Unit> =
        outcome("registerDomainListener", client, domain) { }

    override suspend fun issueAccessKey(client: ClientId, observatory: Observatory): CallOutcome<AccessKey> =
        outcome("issueAccessKey", client) { AccessKey("ak-$client-1761560123456-1") }

    override suspend fun activate(): CallOutcome<Unit> = lifecycle("activate", EnvironmentPhase.Activated)

    override suspend fun start(): CallOutcome<Unit> = lifecycle("start", EnvironmentPhase.Running)

    override suspend fun stop(): CallOutcome<Unit> = lifecycle("stop", EnvironmentPhase.Stopped)

    override suspend fun forceState(modelElement: String, state: String): CallOutcome<Unit> =
        outcome("forceState", modelElement, state) { }

    override suspend fun stateMachineSource(modelElement: String): CallOutcome<String> =
        outcome("stateMachineSource", modelElement) { "" }

    override suspend fun snapshot(): CallOutcome<RuntimeModelSnapshot> = outcome("snapshot") { modelAsRead() }

    override suspend fun subscribeModel(client: ClientId): CallOutcome<Unit> = outcome("subscribeModel", client) { }

    override suspend fun unsubscribeModel(client: ClientId): CallOutcome<Unit> = outcome("unsubscribeModel", client) { }

    /** The model as the runtime would read it now: no objects, and a state for every attached machine once running. */
    fun modelAsRead(): RuntimeModelSnapshot {
        val running = current.value == EnvironmentPhase.Running
        return RuntimeModelSnapshot(
            key,
            runtimePhase(),
            if (running) attached.size.toLong() else 0,
            if (running) attached.associateWith { listOf("on") } else emptyMap(),
            emptyList(),
        )
    }

    /** The phase the runtime would report now. */
    private fun runtimePhase(): RuntimePhase =
        when (current.value) {
            is EnvironmentPhase.Configuring -> RuntimePhase.CONFIGURING
            EnvironmentPhase.Activated -> RuntimePhase.ACTIVATED
            EnvironmentPhase.Halted -> RuntimePhase.HALTED
            EnvironmentPhase.Stopped -> RuntimePhase.STOPPED
            else -> RuntimePhase.RUNNING
        }

    /** The scripted outcome of [call] with [arguments]; on success [effect] runs and its value is answered. */
    private suspend fun <T> outcome(call: String, vararg arguments: Any, effect: () -> T): CallOutcome<T> {
        val failure = script.enter(call, *arguments) ?: return CallOutcome.Done(effect())
        current.value = script.phaseAfterFailure(call) ?: phaseAfter(failure)
        return CallOutcome.Failed(failure)
    }

    /** The scripted outcome of the lifecycle [call], which moves the phase to [reached] on success or cancellation. */
    private suspend fun lifecycle(call: String, reached: EnvironmentPhase): CallOutcome<Unit> =
        try {
            outcome(call) { current.value = reached }
        } catch (cancelled: CancellationException) {
            current.value = reached
            throw cancelled
        }

    /** The phase a call that failed with [failure] leaves, as the real handle reconciles it. */
    private fun phaseAfter(failure: RuntimeFailure): EnvironmentPhase =
        if (failure is RuntimeFailure.Rejected && failure.kind == RejectionKind.UNKNOWN_ENVIRONMENT) {
            EnvironmentPhase.Lost(failure.message)
        } else {
            current.value
        }
}

/**
 * A double of a connection: opening and waiting answer as scripted; it never receives anything.
 *
 * @property environment the environment it belongs to.
 * @property clientId the client it connects as.
 * @param script what it answers and where it records.
 */
internal class ScriptedConnection(
    override val environment: EnvironmentKey,
    override val clientId: ClientId,
    private val script: DeployScript,
) : RuntimeConnection {
    /** The state, written by the calls. */
    private val current = MutableStateFlow<ConnectionState>(ConnectionState.Idle)

    override val state: StateFlow<ConnectionState> = current.asStateFlow()

    override val events: Flow<ConnectionEvent> = emptyFlow()

    override suspend fun open(): ConnectionState {
        script.enter("open")
        current.value = script.openAnswer
        return current.value
    }

    override suspend fun awaitOpen(within: Duration): ConnectionState {
        script.enter("awaitOpen", within)
        if (script.awaitOpenAnswers.isEmpty()) return current.value
        when (val answer = script.awaitOpenAnswers.removeFirst()) {
            null -> {
                current.value = ConnectionState.Reconnecting(2, null)
                delay(within)
            }
            else -> current.value = answer
        }
        return current.value
    }

    override suspend fun send(frame: EventFrame): SendOutcome {
        script.enter("send")
        return SendOutcome.NotConnected(current.value)
    }

    override suspend fun disconnect() {
        script.enter("disconnect")
        current.value = ConnectionState.Ended(ConnectionEnd.Disconnected)
    }

    override fun close() {
        script.log += "close"
    }
}

/**
 * A double of the session's observation: switching on answers the model as the environment double reads it, reads
 * answer as scripted, and both move the environment's phase to the one read, as reading through the real handle does.
 *
 * @param script what it answers and where it records.
 */
internal class ScriptedObservation(private val script: DeployScript) : DeployObservation {
    override suspend fun switchOn(): CallOutcome<RuntimeModelSnapshot> {
        script.enter("switchOn")?.let { return CallOutcome.Failed(it) }
        return read(CallOutcome.Done(environment().modelAsRead()))
    }

    override suspend fun pull(): CallOutcome<RuntimeModelSnapshot> {
        script.pullTimes += script.now()
        script.enter("pull")?.let { return CallOutcome.Failed(it) }
        return read(script.pullAnswers.removeFirstOrNull() ?: CallOutcome.Done(environment().modelAsRead()))
    }

    override fun differences(snapshot: RuntimeModelSnapshot): List<DeployProblem> {
        script.log += "differences(${snapshot.revision})"
        return script.differences
    }

    /** The created environment. */
    private fun environment(): ScriptedEnvironment = checkNotNull(script.environment) { "observed before the creation" }

    /** Answers [outcome], moving the environment to the phase a successful read reports. */
    private fun read(outcome: CallOutcome<RuntimeModelSnapshot>): CallOutcome<RuntimeModelSnapshot> {
        if (outcome is CallOutcome.Done) {
            when (outcome.value.phase) {
                RuntimePhase.CONFIGURING -> Unit
                RuntimePhase.ACTIVATED -> environment().settle(EnvironmentPhase.Activated)
                RuntimePhase.RUNNING -> environment().settle(EnvironmentPhase.Running)
                RuntimePhase.HALTED -> environment().settle(EnvironmentPhase.Halted)
                RuntimePhase.STOPPED -> environment().settle(EnvironmentPhase.Stopped)
            }
        }
        return outcome
    }
}

/**
 * A listener that keeps every published progress and records the reported environment and connection in the script's
 * log.
 *
 * @param script where it records.
 */
internal class RecordingListener(private val script: DeployScript) : DeployListener {
    /** Every published progress, in order. */
    val published: MutableList<DeployProgress> = mutableListOf()

    /** The progress published last. */
    val last: DeployProgress
        get() = published.last()

    override fun progress(progress: DeployProgress) {
        published += progress
    }

    override fun environmentCreated(environment: RuntimeEnvironment) {
        script.log += "environmentCreated(${environment.key})"
    }

    override fun connected(connection: RuntimeConnection) {
        script.log += "connected(${connection.clientId})"
    }
}
