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
package io.karpfen.ide.bridges.ros

import io.karpfen.ide.bridges.api.Bridge
import io.karpfen.ide.bridges.api.BridgeConfig
import io.karpfen.ide.bridges.api.BridgeKind
import io.karpfen.ide.bridges.api.ModelLink
import io.karpfen.ide.bridges.mapping.RoutedBridge
import kotlinx.coroutines.CoroutineScope

/**
 * The ROS bridge for [config] between the model behind [link] and a ROS system, reached through its rosbridge server.
 * The bridge works in [scope]. It talks to ROS 2 unless the settings of [config] say `ros1`.
 *
 * For the lamp example a bridge file with the inbound route `/lamp/press` to the event `switch/press` lets any ROS
 * node press the wall switch by publishing on `/lamp/press`, for example with
 * `ros2 topic pub --once /lamp/press std_msgs/msg/Empty`. An outbound route for the property `bulb/watts` with the
 * type `std_msgs/msg/Float64` publishes the new value on its topic whenever the bulb changes its power.
 */
public fun RosBridge(config: BridgeConfig, link: ModelLink, scope: CoroutineScope): Bridge {
    require(config.kind == BridgeKind.ROS) { "the configuration is for ${config.kind.id}, not for ros" }
    val endpoint = RosEndpoint(
        settings = RosSettings.of(config.settings),
        subscriptions = config.inbound.associate { it.from to it.messageType },
        advertisements = config.outbound.associate { it.to to it.messageType },
    )
    return RoutedBridge(config, endpoint, link, scope)
}
