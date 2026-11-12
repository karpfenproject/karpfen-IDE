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
package io.karpfen.ide.notation.diagram

/**
 * Identity of the `notation-diagram` module. The module holds the diagrams without any UI: what a diagram shows and
 * where its elements go. The package `statechart` holds the statechart of a state machine. The package `structure`
 * holds the diagram of boxes and links that serves as the class diagram of a metamodel and as the object diagram of a
 * model. The package `layout` places both. Start reading at `StatechartProjection`, `MetamodelProjection` and
 * `DiagramArrangement`.
 */
public object NotationDiagramModule {
    /** The module's name, which is also the name of its directory. */
    public const val ID: String = "notation-diagram"
}
