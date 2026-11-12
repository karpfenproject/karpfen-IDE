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

import io.karpfen.ide.bridges.api.Bridge
import io.karpfen.ide.bridges.api.BridgeConfig
import io.karpfen.ide.bridges.api.BridgeKind
import io.karpfen.ide.bridges.api.ModelLink
import io.karpfen.ide.bridges.mapping.RoutedBridge
import kotlinx.coroutines.CoroutineScope

/**
 * The MQTT bridge for [config] between the model behind [link] and an MQTT broker. The bridge works in [scope].
 *
 * For the lamp example a bridge file with the inbound route `home/switch` to the event `switch/press` lets any MQTT
 * client press the wall switch by publishing on `home/switch`. An outbound route for the property `bulb/watts`
 * publishes the new value on its topic whenever the bulb changes its power.
 */
public fun MqttBridge(config: BridgeConfig, link: ModelLink, scope: CoroutineScope): Bridge {
    require(config.kind == BridgeKind.MQTT) { "the configuration is for ${config.kind.id}, not for mqtt" }
    return RoutedBridge(config, MqttEndpoint(MqttSettings.of(config.settings)), link, scope)
}
