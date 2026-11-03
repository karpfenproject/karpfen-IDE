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
package io.karpfen.ide.runtime.session.plan

import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import java.nio.file.Path

/**
 * Copies the declarations of objects, types and states from the syntax index of analyzed files into
 * [DeclarationLocations]. Stateless.
 */
internal object DeclarationIndex {
    /**
     * The locations of the declarations in [files] as [analysis] parsed them, each name at its first declaration in a
     * file, objects without a key left out. A file the analysis does not hold contributes nothing.
     */
    fun locationsOf(analysis: ProjectAnalysis, files: List<Path>): DeclarationLocations {
        val objects = LinkedHashMap<Path, Map<String, TextRange>>()
        val types = LinkedHashMap<Path, Map<String, TextRange>>()
        val states = LinkedHashMap<Path, Map<String, TextRange>>()
        val snapshots = LinkedHashMap<Path, TextSnapshot>()
        for (file in files) {
            val analyzed = analysis.file(file) ?: continue
            val index = analyzed.parse.index
            objects[file] = firstDeclarations(index, SymbolKind.OBJECT)
            types[file] = firstDeclarations(index, SymbolKind.TYPE)
            states[file] = firstDeclarations(index, SymbolKind.STATE)
            snapshots[file] = analyzed.snapshot
        }
        return DeclarationLocations(objects, types, states, snapshots)
    }

    /** The name ranges of the declarations of [kind] in [index], each non-blank name at its first declaration. */
    private fun firstDeclarations(index: SyntaxIndex, kind: SymbolKind): Map<String, TextRange> {
        val ranges = LinkedHashMap<String, TextRange>()
        index.declarationsOf(kind).filter { it.name.isNotBlank() }.forEach { ranges.putIfAbsent(it.name, it.nameRange) }
        return ranges
    }
}
