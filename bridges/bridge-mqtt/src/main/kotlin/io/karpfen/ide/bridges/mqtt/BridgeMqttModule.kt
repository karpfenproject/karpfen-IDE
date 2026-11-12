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

/**
 * Identity of the `bridge-mqtt` module. The module holds the MQTT bridge. A message on a topic becomes an event of
 * the model, and a change of the model is published on a topic. Start reading at [MqttBridge] and [MqttEndpoint].
 */
public object BridgeMqttModule {
    /** The module's name, which is also the name of its directory. */
    public const val ID: String = "bridge-mqtt"
}
