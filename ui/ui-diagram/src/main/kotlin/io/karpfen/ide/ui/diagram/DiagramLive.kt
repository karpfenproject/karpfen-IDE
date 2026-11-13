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
 * What a running model adds to the diagram of one of its files.
 *
 * @property active the ids of the boxes that are active now, which the diagram marks. In a statechart these are the
 *   states the state machine is in.
 * @property values the properties the objects of the running model have now, by the key of the object, each as its
 *   name and its value. An object diagram shows them in place of the values of the text.
 */
public data class DiagramLive(
    public val active: Set<String> = emptySet(),
    public val values: Map<String, List<Pair<String, String>>> = emptyMap(),
)
