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

/**
 * What an environment holds of state machines, as `GET /observatory/stateMachines` answers it. Immutable.
 *
 * @property attached the keys of the objects that have a state machine uploaded with `attachedTo`, in upload order.
 * @property definitions the attachable state machines, in upload order.
 * @property instances the state machine instances in the order in which they step. They are empty before activation,
 *   the instances the start will build until the first tick, and those of the latest tick after it.
 */
public data class StateMachineHoldings(
    public val attached: List<String>,
    public val definitions: List<HeldDefinition>,
    public val instances: List<HeldInstance>,
)

/**
 * An attachable state machine an environment holds.
 *
 * @property name its name.
 * @property types the types it lists after `ATTACHABLE TO`, in their order.
 */
public data class HeldDefinition(public val name: String, public val types: List<String>)

/**
 * A state machine instance of an environment.
 *
 * @property modelElement the key of the object it runs on.
 * @property stateMachine the name of the attachable state machine it runs, or null for a state machine attached by the
 *   run configuration.
 */
public data class HeldInstance(public val modelElement: String, public val stateMachine: String?)
