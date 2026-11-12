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
package io.karpfen.ide.bridges.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import io.karpfen.ide.bridges.api.BridgeEndpoint
import io.karpfen.ide.bridges.api.Delivery
import io.karpfen.ide.bridges.api.ExternalMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout

/**
 * The MQTT side of a bridge: one connection to a broker, with one subscription per inbound route.
 *
 * The `from` of an inbound route is a topic filter and may use the wildcards of MQTT, `+` for one level and `#` for
 * the rest. Every message that matches the filter is one message for that route, with the payload read as UTF-8
 * text. The `to` of an outbound route is the topic the message is published on.
 *
 * The connection speaks MQTT 3.1.1, which every broker understands. If it breaks, the client connects again on its
 * own and subscribes again, and outbound messages fail in the meantime. Opening waits for the broker for the timeout
 * of [settings] and tells in words why it could not connect.
 */
public class MqttEndpoint(private val settings: MqttSettings) : BridgeEndpoint {

    private val arriving = MutableSharedFlow<ExternalMessage>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val qos: MqttQos = checkNotNull(MqttQos.fromCode(settings.qos)) { "no quality of service ${settings.qos}" }

    @Volatile
    private var client: Mqtt3AsyncClient? = null

    override val incoming: Flow<ExternalMessage> get() = arriving

    override suspend fun open(addresses: Set<String>): Delivery {
        if (client != null) return Delivery.Delivered
        val created = try {
            build()
        } catch (e: IllegalArgumentException) {
            return Delivery.Failed("The settings of the MQTT bridge are not usable: ${e.message}.")
        }
        try {
            withTimeout(settings.timeoutMs) {
                created.connectWith().cleanSession(true).send().await()
                for (address in addresses) subscribe(created, address)
            }
        } catch (e: TimeoutCancellationException) {
            created.disconnect()
            return Delivery.Failed("The MQTT broker at ${settings.address} did not answer within ${settings.timeoutMs} ms.")
        } catch (e: CancellationException) {
            created.disconnect()
            throw e
        } catch (e: UnusableFilter) {
            created.disconnect()
            return Delivery.Failed("\"${e.filter}\" is no topic that can be subscribed to.")
        } catch (e: Exception) {
            created.disconnect()
            return Delivery.Failed("The MQTT broker at ${settings.address} could not be reached: ${reasonOf(e)}.")
        }
        client = created
        return Delivery.Delivered
    }

    override suspend fun publish(message: ExternalMessage): Delivery {
        val connected = client ?: return Delivery.Failed("The MQTT bridge is not connected.")
        return try {
            connected.publishWith().topic(message.address).payload(message.body.encodeToByteArray()).qos(qos).retain(settings.retain).send().await()
            Delivery.Delivered
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            Delivery.Failed("\"${message.address}\" is no topic that can be published on.")
        } catch (e: Exception) {
            Delivery.Failed("The message for ${message.address} did not reach the MQTT broker: ${reasonOf(e)}.")
        }
    }

    override suspend fun close() {
        val connected = client ?: return
        client = null
        runCatching { connected.disconnect().await() }
    }

    /** The client for the broker of [settings], not connected yet. */
    private fun build(): Mqtt3AsyncClient {
        var builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier(settings.clientId)
            .serverHost(settings.host)
            .serverPort(settings.port)
            .automaticReconnectWithDefaultConfig()
        if (settings.tls) builder = builder.sslWithDefaultConfig()
        settings.username?.let { name ->
            var auth = builder.simpleAuth().username(name)
            settings.password?.let { auth = auth.password(it.encodeToByteArray()) }
            builder = auth.applySimpleAuth()
        }
        return builder.buildAsync()
    }

    /** Subscribes to the topic filter [address]. Every message for it arrives with [address] as its address. */
    private suspend fun subscribe(client: Mqtt3AsyncClient, address: String) {
        val subscription = try {
            client.subscribeWith().topicFilter(address).qos(qos)
        } catch (e: IllegalArgumentException) {
            throw UnusableFilter(address)
        }
        subscription
            .callback { publish -> arriving.tryEmit(ExternalMessage(address, publish.payloadAsBytes.decodeToString())) }
            .send()
            .await()
    }

    /** Why [failure] happened, in the words of its innermost cause. */
    private fun reasonOf(failure: Throwable): String {
        val cause = generateSequence(failure) { it.cause }.last()
        return cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
    }

    /** Thrown inside [open] for a topic filter the client refuses. */
    private class UnusableFilter(val filter: String) : RuntimeException()

    private companion object {
        /** How many inbound messages may wait until the bridge reads them. Older ones are dropped beyond that. */
        const val BUFFER = 256
    }
}
