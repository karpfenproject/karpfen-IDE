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
package io.karpfen.ide.notation.diagram.layout

import io.karpfen.ide.notation.diagram.structure.StructureDiagram

/**
 * Places the elements of a class or object diagram automatically. What owns something stands above it, the links run
 * around the boxes, and labels get room of their own.
 */
public fun interface StructureLayouter {
    /** The layout of [diagram], with texts as wide as [text] says. */
    public fun layout(diagram: StructureDiagram, text: TextMeasurer): StructureLayout
}

/**
 * The layouter the IDE uses. It hands the work to the Eclipse Layout Kernel, a library for layered graph layout. The
 * library stays behind this function, so another layouter can take its place.
 */
public fun StructureLayouter(): StructureLayouter = ElkStructureLayouter()
