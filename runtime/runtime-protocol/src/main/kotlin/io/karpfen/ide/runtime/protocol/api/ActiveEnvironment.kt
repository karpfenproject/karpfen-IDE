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
package io.karpfen.ide.runtime.protocol.api

import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey

/**
 * An environment the runtime lists. The list holds activated, running, halted and stopped environments. Environments
 * that are still configuring are not listed.
 *
 * @property key the environment.
 * @property modelElements the `attachedTo` keys of its state machines, in the order the runtime lists them.
 */
public data class ActiveEnvironment(public val key: EnvironmentKey, public val modelElements: List<String>)
