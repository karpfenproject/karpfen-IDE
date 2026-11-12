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
package io.karpfen.ide.bridges.mapping

import io.karpfen.ide.bridges.api.Bridge
import io.karpfen.ide.bridges.api.BridgeConfig
import io.karpfen.ide.bridges.api.BridgeEndpoint
import io.karpfen.ide.bridges.api.BridgeState
import io.karpfen.ide.bridges.api.BridgeStatus
import io.karpfen.ide.bridges.api.Delivery
import io.karpfen.ide.bridges.api.ExternalMessage
import io.karpfen.ide.bridges.api.FlowPolicy
import io.karpfen.ide.bridges.api.ModelLink
import io.karpfen.ide.bridges.api.OutboundSource
import io.karpfen.ide.bridges.api.RouteStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The bridge that runs the routes of a [BridgeConfig] between an [endpoint] and a [link]. It is the same for every
 * kind of external system. The kind only decides which endpoint it gets.
 *
 * Every route has a waiting line of its own and one worker that empties it, so a slow route never holds up another.
 * The flow policy of the route decides what the line keeps. A queue keeps the messages in order up to its limit and
 * then drops the oldest. Latest keeps only the newest. Sample also keeps only the newest and hands on one message per
 * period. Every dropped message is counted.
 *
 * An inbound message is mapped to its event by [Mappings.toEvent] and sent to the model. A message that cannot be
 * mapped or sent counts as failed, with the reason kept in the status of the route. An outbound message is made by
 * [Mappings.toMessage] from what the model reports. A property route and a state route send a value only when it
 * differs from the one they sent last, because the model reports an object whenever any of its properties changes.
 *
 * The bridge works in a child scope of [scope]. [stop] ends that work, and so does cancelling [scope].
 */
public class RoutedBridge(
    private val config: BridgeConfig,
    private val endpoint: BridgeEndpoint,
    private val link: ModelLink,
    private val scope: CoroutineScope,
) : Bridge {

    private val current = MutableStateFlow(
        BridgeStatus(
            inbound = config.inbound.map { RouteStatus(Mappings.label(it)) },
            outbound = config.outbound.map { RouteStatus(Mappings.label(it)) },
        ),
    )
    private val lifecycle = Mutex()
    private var work: Job? = null

    override val status: StateFlow<BridgeStatus> get() = current.asStateFlow()

    override suspend fun start(): Unit = lifecycle.withLock {
        if (work != null) return
        current.update { it.copy(state = BridgeState.STARTING, problem = null) }
        when (val opened = endpoint.open(config.inbound.map { it.from }.toSet())) {
            is Delivery.Failed -> current.update { it.copy(state = BridgeState.FAILED, problem = opened.reason) }
            Delivery.Delivered -> {
                work = run()
                current.update { it.copy(state = BridgeState.RUNNING) }
            }
        }
    }

    override suspend fun stop(): Unit = lifecycle.withLock {
        val running = work ?: return
        work = null
        running.cancelAndJoin()
        endpoint.close()
        current.update { it.copy(state = BridgeState.STOPPED) }
    }

    /** Starts the workers of all routes and the two readers that feed them, and returns the job that holds them. */
    private fun run(): Job {
        val job = SupervisorJob(scope.coroutineContext.job)
        val workers = CoroutineScope(scope.coroutineContext + job)
        val inbound = config.inbound.mapIndexed { index, route ->
            val line = line<ExternalMessage>(route.flow) { count(index, inbound = true) { it.copy(dropped = it.dropped + 1) } }
            workers.launch {
                for (message in line) {
                    val outcome = when (val mapping = Mappings.toEvent(route, message)) {
                        is InboundMapping.Mapped -> link.send(mapping.event)
                        is InboundMapping.Rejected -> Delivery.Failed(mapping.reason)
                    }
                    record(index, inbound = true, outcome)
                    pause(route.flow)
                }
            }
            route.from to line
        }
        val outbound = config.outbound.mapIndexed { index, route ->
            val line = line<ExternalMessage>(route.flow) { count(index, inbound = false) { it.copy(dropped = it.dropped + 1) } }
            workers.launch {
                for (message in line) {
                    record(index, inbound = false, endpoint.publish(message))
                    pause(route.flow)
                }
            }
            line
        }
        workers.launch(start = CoroutineStart.UNDISPATCHED) {
            endpoint.incoming.collect { message ->
                inbound.forEachIndexed { index, (from, line) ->
                    if (from == message.address) {
                        count(index, inbound = true) { it.copy(received = it.received + 1) }
                        line.trySend(message)
                    }
                }
            }
        }
        workers.launch(start = CoroutineStart.UNDISPATCHED) {
            val lastBodies = arrayOfNulls<String>(config.outbound.size)
            link.pushes.collect { push ->
                config.outbound.forEachIndexed { index, route ->
                    val message = Mappings.toMessage(route, push) ?: return@forEachIndexed
                    if (route.source.isValue && lastBodies[index] == message.body) return@forEachIndexed
                    lastBodies[index] = message.body
                    count(index, inbound = false) { it.copy(received = it.received + 1) }
                    outbound[index].trySend(message)
                }
            }
        }
        return job
    }

    /** The waiting line of a route with the flow policy [flow]. [onDrop] runs for every message the line drops. */
    private fun <T> line(flow: FlowPolicy, onDrop: () -> Unit): Channel<T> = when (flow) {
        is FlowPolicy.Queue -> Channel(flow.limit, BufferOverflow.DROP_OLDEST) { onDrop() }
        FlowPolicy.Latest, is FlowPolicy.Sample -> Channel(1, BufferOverflow.DROP_OLDEST) { onDrop() }
    }

    /** Waits out the period of a sampling route after a message was handed on. Other policies do not wait. */
    private suspend fun pause(flow: FlowPolicy) {
        if (flow is FlowPolicy.Sample) delay(flow.periodMs)
    }

    private fun record(index: Int, inbound: Boolean, outcome: Delivery) = count(index, inbound) { route ->
        when (outcome) {
            Delivery.Delivered -> route.copy(delivered = route.delivered + 1)
            is Delivery.Failed -> route.copy(failed = route.failed + 1, lastError = outcome.reason)
        }
    }

    /** Changes the status of the route at [index] of the inbound or the outbound routes. */
    private fun count(index: Int, inbound: Boolean, change: (RouteStatus) -> RouteStatus) {
        current.update { status ->
            val routes = if (inbound) status.inbound else status.outbound
            val changed = routes.mapIndexed { at, route -> if (at == index) change(route) else route }
            if (inbound) status.copy(inbound = changed) else status.copy(outbound = changed)
        }
    }
}

/** True for a source that has a value at every moment, so that only a change of the value is worth a message. */
private val OutboundSource.isValue: Boolean
    get() = this !is OutboundSource.Event
