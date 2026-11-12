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

/**
 * Identity of the `bridge-mapping` module. The module turns a bridge file into a working bridge. It reads and writes
 * the file, maps messages to events and model changes to messages, and runs the routes with their flow policies. It
 * knows no external system. Start reading at [BridgeConfigFile] and [RoutedBridge].
 */
public object BridgeMappingModule {
    /** The module's name, which is also the name of its directory. */
    public const val ID: String = "bridge-mapping"
}
