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

import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.lang.modelpath.PathType
import io.karpfen.ide.core.lang.symbols.TypeExpression

/**
 * The RETURNS and TAKES types of the macros of a document. They come from its syntax indexes, which are in priority
 * order, and are looked up by name.
 */
internal class MacroSignatures(private val indexes: List<SyntaxIndex>) {

    /** The RETURNS type of [macro], or null if unknown. `reference(T)` counts as `T`. */
    fun returns(macro: String): PathType? =
        indexes.firstNotNullOfOrNull { it.declarations(SymbolKind.MACRO, macro).firstOrNull() }?.typeName?.let(::pathTypeOf)

    /** The TAKES parameters of [macro] with their types, in declaration order. */
    fun parameters(macro: String): List<Pair<String, PathType>> =
        indexes.firstNotNullOfOrNull { index ->
            index.declarationsOf(SymbolKind.MACRO_PARAMETER)
                .filter { it.container == macro && it.name.isNotEmpty() }
                .takeIf { it.isNotEmpty() }
        }.orEmpty().map { it.name to pathTypeOf(it.typeName) }

    /** The path type of a type as written in TAKES or RETURNS. */
    private fun pathTypeOf(written: String?): PathType =
        TypeExpression.parse(written).let { PathType(it.name, it.isList, it.isPrimitive) }
}
