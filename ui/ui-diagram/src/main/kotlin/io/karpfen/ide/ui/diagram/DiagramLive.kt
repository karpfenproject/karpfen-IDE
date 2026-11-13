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
package io.karpfen.ide.ui.diagram

/**
 * What a running model adds to the diagram of one of its files: the states its state machine is in, which a
 * statechart marks. The other diagrams show nothing of a run.
 *
 * @property active the ids of the boxes that are active now. In a statechart these are the names of the active
 *   states, the outer ones included.
 */
public data class DiagramLive(public val active: Set<String> = emptySet())
