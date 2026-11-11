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
package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolDeclaration
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.modelpath.pathSegmentsOf
import io.karpfen.ide.core.lang.symbols.DocumentDeclarations

/**
 * The references within one KStates document of states, macros, WITH bindings and macro parameters. Declarations are
 * found by name with [DocumentDeclarations], following the staleness rule. References come from the latest parse
 * outcome of the document. Each request makes its own instance.
 */
internal class KstatesLocalReferences(private val document: LanguageDocument) {

    private val declarations = DocumentDeclarations(document)

    /** The declarations of [kind] named [name] and the references of [reference] to it. */
    fun of(kind: SymbolKind, reference: ReferenceKind, name: String): List<Location> {
        val references = declarations.latest()?.let { source ->
            source.outcome.index.references(reference, name).map { source.location(it.range) }
        }.orEmpty()
        return declarations.named(kind, name).map { it.location } + references
    }

    /**
     * The uses of the WITH binding or macro parameter [name] in scope at [offset]. These are the first segments of the
     * model paths that start with it. They are searched in the innermost WITH block that binds it, after its `AS "x"`,
     * or in the enclosing macro that declares it. The lookup works by offset, so by the staleness rule it needs a parse
     * of the current text. Otherwise the result is empty.
     */
    fun ofBinding(name: String, offset: Int): List<Location> {
        val source = declarations.latest()?.takeIf { it.outcome.version == document.text.version } ?: return emptyList()
        val index = source.outcome.index
        val scope = index.enclosingDeclarations(offset).lastOrNull { declares(index, it, name) } ?: return emptyList()
        val start = if (scope.kind == SymbolKind.BINDING) scope.nameRange.end else scope.range.start
        return index.referencesOf(ReferenceKind.MODEL_PATH)
            .filter { it.range.start >= start && it.range in scope.range }
            .mapNotNull { reference ->
                pathSegmentsOf(reference.name, reference.range, document.text.text).firstOrNull()?.takeIf { it.name == name }?.range
            }
            .map(source::location)
    }

    /** True if [declaration] is the binding [name] or a macro with the parameter [name]. */
    private fun declares(index: SyntaxIndex, declaration: SymbolDeclaration, name: String): Boolean = when (declaration.kind) {
        SymbolKind.BINDING -> declaration.name == name
        SymbolKind.MACRO -> index.declarationsOf(SymbolKind.MACRO_PARAMETER).any { it.container == declaration.name && it.name == name }
        else -> false
    }
}
