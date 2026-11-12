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
package io.karpfen.ide.bridges.api

/**
 * Identity of the `bridge-api` module. A bridge connects a running karpfen model to an external system, such as an
 * HTTP client, an MQTT broker or ROS. This module holds what all bridges share and nothing that belongs to one
 * external system: the configuration of a bridge with its routes, the two sides a bridge stands between, and its
 * lifecycle. Start reading at [Bridge], [BridgeConfig], [BridgeEndpoint] and [ModelLink].
 */
public object BridgeApiModule {
    /** The module's name, which is also the name of its directory. */
    public const val ID: String = "bridge-api"
}
