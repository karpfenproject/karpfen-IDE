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
package io.karpfen.ide.bridges.http

import io.karpfen.ide.bridges.api.Bridge
import io.karpfen.ide.bridges.api.BridgeConfig
import io.karpfen.ide.bridges.api.BridgeKind
import io.karpfen.ide.bridges.api.ModelLink
import io.karpfen.ide.bridges.mapping.RoutedBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * The HTTP bridge for [config] between the model behind [link] and whoever sends HTTP requests. The bridge works in
 * [scope]. Its server is started and stopped on [ioDispatcher], which blocks for a moment.
 *
 * For the lamp example a bridge file with the inbound route `/switch/press` to the event `switch/press` makes
 * `curl -X POST http://127.0.0.1:8090/switch/press` press the wall switch. An outbound route for the property
 * `bulb/watts` posts the new value to its URL whenever the bulb changes its power.
 */
public fun HttpBridge(
    config: BridgeConfig,
    link: ModelLink,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): Bridge {
    require(config.kind == BridgeKind.HTTP) { "the configuration is for ${config.kind.id}, not for http" }
    return RoutedBridge(config, HttpEndpoint(HttpSettings.of(config.settings), ioDispatcher), link, scope)
}
