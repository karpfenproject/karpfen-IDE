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
package io.karpfen.ide.notation.diagram.structure

/** The longest row with a current value in characters. */
private const val MAX_ROW = 48

/** The mark of a row that was cut. */
private const val ELLIPSIS = "…"

/**
 * This object diagram with the values a running model has now. [values] holds the properties of each object by the
 * key of the object, each as its name and its value. A row of an object shows the current value of its property
 * in place of the value the text gives it. A property that the text does not set, but the running model has, gets a
 * row of its own behind the others. Objects that [values] does not name stay as they are, and so does a class
 * diagram.
 */
public fun StructureDiagram.withValues(values: Map<String, List<Pair<String, String>>>): StructureDiagram {
    if (kind != StructureKind.OBJECTS || values.isEmpty()) return this
    return copy(
        nodes = nodes.map { node ->
            val now = values[node.id] ?: return@map node
            val byName = now.toMap()
            val written = node.rows.map { it.substringBefore(" = ") }
            val shown = node.rows.zip(written) { row, name -> byName[name]?.let { rowOf(name, it) } ?: row }
            node.copy(rows = shown + now.filter { it.first !in written }.map { rowOf(it.first, it.second) })
        },
    )
}

private fun rowOf(name: String, value: String): String {
    val row = "$name = $value"
    return if (row.length <= MAX_ROW) row else row.take(MAX_ROW - 1) + ELLIPSIS
}
